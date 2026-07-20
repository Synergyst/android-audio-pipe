// server_user.cpp — Standalone binary for PulseAudio audio pipe to Android.
//
// Auto-detects root vs standard user and adapts accordingly:
//   - Root: starts PulseAudio if not running (with proper XDG_RUNTIME_DIR), creates virtual devices
//   - Standard user: connects to existing PulseAudio or starts it if needed, creates virtual devices
//
// Works in all setups:
//   - PulseAudio already running (user or system mode)
//   - PulseAudio not running (starts it with correct environment)
//   - Root or non-root user
//
// No bash script needed — just run the binary directly.

#include "miniaudio.h"

#include <iostream>
#include <vector>
#include <string>
#include <thread>
#include <atomic>
#include <cstring>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <pwd.h>
#include <pthread.h>
#include <fcntl.h>
#include <poll.h>
#include <csignal>
#include <iomanip>
#include <chrono>
#include <deque>
#include <numeric>
#include <mutex>
#include <cmath>
#include <random>
#include <cstdlib>
#include <queue>
#include <sstream>
#include <algorithm>

// ── Configuration ──────────────────────────────────────────────────────────

const int RECV_PORT = 12345;
const int SEND_PORT = 12346;

const unsigned int HARDWARE_SAMPLE_RATE = 44100;
std::atomic<unsigned int> current_network_rate{44100};
const unsigned int CHANNELS = 1;
const size_t BUFFER_SIZE = 512;

enum class RunMode { MIC, SPEAKER, DUPLEX };
RunMode current_mode = RunMode::DUPLEX;

// Virtual Device Names for PulseAudio
const char* MIC_SINK_NAME    = "AndroidPipe_MicSink";
const char* MIC_SOURCE_NAME  = "AndroidPipe_Mic";
const char* MIC_SINK_DESC    = "Android_Mic_Internal";
const char* MIC_SOURCE_DESC  = "Android_Phone_Microphone";

const char* SPEAKER_SINK_NAME    = "AndroidPipe_Speaker";
const char* SPEAKER_SOURCE_NAME  = "AndroidPipe_SpeakerMonitor";
const char* SPEAKER_SINK_DESC    = "Android_Phone_Speaker";
const char* SPEAKER_SOURCE_DESC  = "Android_Speaker_Internal";

// TCP Control port for connecting to phone's TcpControlServer
const int PHONE_TCP_CONTROL_PORT = 12347;
const int TCP_RECONNECT_INTERVAL_MS = 3000;

// ── Packet Protocol ────────────────────────────────────────────────────────

std::string phoneIp = "192.168.168.120";
static std::mutex phoneIpMutex;

enum PacketType : uint8_t {
    TYPE_AUDIO = 0x01,
    TYPE_PING = 0x02,
    TYPE_PONG = 0x03,
    TYPE_STATS = 0x04,
    TYPE_HANDSHAKE_REQ = 0x05,
    TYPE_HANDSHAKE_RESP = 0x06,
    TYPE_NEGOTIATE = 0x07,
    TYPE_DISCONNECT = 0x08,
};

#pragma pack(push, 1)
struct PacketHeader {
    uint8_t type;
    uint8_t session_id[3];
    uint32_t sequence;
};
#pragma pack(pop)

// ── Connection Stats ───────────────────────────────────────────────────────

struct ConnectionStats {
    std::atomic<uint64_t> packets_received{0};
    std::atomic<uint64_t> packets_lost{0};
    std::atomic<uint32_t> last_sequence{0};
    std::atomic<long long>  last_seen_ms{0};
    std::atomic<bool> connected{false};

    std::deque<int> loss_window;
    std::mutex window_mtx;
    const size_t WINDOW_SIZE = 100;

    void record_loss(int lost_count) {
        std::lock_guard<std::mutex> lock(window_mtx);
        loss_window.push_back(lost_count);
        if (loss_window.size() > WINDOW_SIZE) loss_window.pop_front();
    }

    double get_rolling_loss_pct() {
        std::lock_guard<std::mutex> lock(window_mtx);
        if (loss_window.empty()) return 0.0;
        long long total_lost = std::accumulate(loss_window.begin(), loss_window.end(), 0LL);
        return (double)total_lost / (double)WINDOW_SIZE * 100.0;
    }
};

// ── Ring Buffer ────────────────────────────────────────────────────────────

template <typename T>
class AudioRingBuffer {
    std::vector<T> buffer;
    size_t head = 0, tail = 0, size = 0;
    std::mutex mtx;

public:
    AudioRingBuffer(size_t capacity) : buffer(capacity) {}

    size_t write(const T* data, size_t count) {
        std::lock_guard<std::mutex> lock(mtx);
        size_t written = 0;
        while (written < count && size < buffer.size()) {
            buffer[head] = data[written++];
            head = (head + 1) % buffer.size();
            size++;
        }
        return written;
    }

    size_t read(T* data, size_t count) {
        std::lock_guard<std::mutex> lock(mtx);
        size_t read_count = 0;
        while (read_count < count && size > 0) {
            data[read_count++] = buffer[tail];
            tail = (tail + 1) % buffer.size();
            size--;
        }
        return read_count;
    }

    size_t available_to_read() {
        std::lock_guard<std::mutex> lock(mtx);
        return size;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mtx);
        head = tail = size = 0;
    }
};

// ── Jitter Buffer ──────────────────────────────────────────────────────────

struct AudioPacket {
    uint32_t sequence;
    std::vector<int16_t> samples;
    bool operator>(const AudioPacket& other) const { return sequence > other.sequence; }
};

std::priority_queue<AudioPacket, std::vector<AudioPacket>, std::greater<AudioPacket>> jitterBuffer;
std::mutex jitterMtx;
uint32_t nextExpectedSeq = 0;
bool firstPacketReceived = false;
const size_t JITTER_THRESHOLD_MIN = 3;
const size_t JITTER_THRESHOLD_MAX = 20;
const size_t DRIFT_THRESHOLD = 30;
const size_t MAX_JITTER_SIZE = 50;
std::atomic<size_t> adaptive_jitter_threshold{3};

// ── Globals ────────────────────────────────────────────────────────────────

std::atomic<bool> running(true);
std::atomic<bool> null_mode(false);
std::atomic<bool> test_tone_mode(false);
ConnectionStats stats;
std::atomic<int> g_tcp_sock{-1};  // TCP client socket fd — used by main() to unblock shutdown
uint8_t CURRENT_SESSION[3] = {0xDE, 0xAD, 0xBE};  // Pre-seed to Android app default so pre-handshake audio packets pass the session check.
// The real session ID is assigned during handshake and overwrites this.
std::mutex session_mtx;

AudioRingBuffer<int16_t> captureBuffer(192000);
AudioRingBuffer<int16_t> playbackBuffer(192000);

// ── User Detection ─────────────────────────────────────────────────────────

static uid_t get_current_uid() {
    return geteuid();
}

static bool is_root() {
    return get_current_uid() == 0;
}

static std::string get_runtime_dir() {
    const char* xdg = getenv("XDG_RUNTIME_DIR");
    if (xdg && xdg[0] != '\0') return xdg;
    return "/run/user/" + std::to_string(get_current_uid());
}

static std::string get_pulse_socket_path(const std::string& rt_dir) {
    return rt_dir + "/pulse/native";
}

// ── PulseAudio Management ──────────────────────────────────────────────────



static bool pulseaudio_is_running() {
    // Try pactl info — if it succeeds, PA is running and reachable
    int rc = system("pactl info > /dev/null 2>&1");
    return (rc == 0);
}

static std::string get_pulse_server_env() {
    const char* ps = getenv("PULSE_SERVER");
    if (ps && ps[0] != '\0') return ps;
    return "";
}

/**
 * Ensure PulseAudio is running and reachable.
 *
 * Handles all scenarios robustly:
 *   - PA already running (user or system mode): uses existing instance
 *   - PA not running (root): starts with proper XDG_RUNTIME_DIR set
 *   - PA not running (non-root): tries to start, errors if it fails
 *
 * Key fix: XDG_RUNTIME_DIR MUST be set before PulseAudio starts, otherwise
 * PulseAudio silently fails to create its socket directory. This is a known
 * PulseAudio behavior — it checks XDG_RUNTIME_DIR first, and if not set,
 * falls back to /run/user/<uid> but fails to create it due to permission
 * checks. Setting XDG_RUNTIME_DIR explicitly resolves this.
 */
static void ensure_pulseaudio_running() {
    // Check if PulseAudio is already running and reachable.
    // pactl auto-discovers the socket regardless of location.
    if (pulseaudio_is_running()) {
        std::cout << "[System] PulseAudio is already running. Using existing instance." << std::endl;

        // Unset DISPLAY before any pactl/libpulse calls — libpulse checks
        // DISPLAY to decide whether to connect to X11 via D-Bus. Without
        // this, libpulse produces "xcb_connection_has_error()" spam on headless
        // systems where X11 is unavailable.
        unsetenv("DISPLAY");

        // Set PULSE_SERVER so all subsequent pactl/libpulse calls use the
        // Unix socket directly, bypassing D-Bus X11 connection attempts.
        if (get_pulse_server_env().empty()) {
            std::string rt_dir = get_runtime_dir();
            std::string user_socket = rt_dir + "/pulse/native";
            struct stat st;
            if (stat(user_socket.c_str(), &st) == 0) {
                setenv("PULSE_SERVER", ("unix:" + user_socket).c_str(), 1);
                std::cout << "[System] Connected to user-mode PulseAudio." << std::endl;
            } else if (stat("/run/pulse/native", &st) == 0) {
                setenv("PULSE_SERVER", "unix:/run/pulse/native", 1);
                std::cout << "[System] Connected to system-mode PulseAudio." << std::endl;
            } else {
                std::cout << "[System] Using default PulseAudio server." << std::endl;
            }
        }
        return;
    }

    // PulseAudio is not running. We need to start it.
    std::cout << "[System] PulseAudio is not running. Starting..." << std::endl;

    uid_t uid = get_current_uid();

    // Determine the runtime directory. get_runtime_dir() checks XDG_RUNTIME_DIR
    // first, and falls back to /run/user/<uid>.
    std::string rt_dir = get_runtime_dir();

    // Ensure the runtime directory exists.
    // /run/user must exist as a directory first.
    mkdir("/run/user", 0755);
    mkdir(rt_dir.c_str(), 0700);

    // CRITICAL: XDG_RUNTIME_DIR must be set before PulseAudio starts.
    // PulseAudio uses this to determine where to create its socket directory.
    // Without it, PulseAudio silently fails with "Permission denied" on root
    // because it can't create the fallback directory.
    if (getenv("XDG_RUNTIME_DIR") == nullptr || getenv("XDG_RUNTIME_DIR")[0] == '\0') {
        setenv("XDG_RUNTIME_DIR", rt_dir.c_str(), 1);
    }

    // Unset DISPLAY to prevent PulseAudio from trying X11/XCB when no X
    // server is available. Without this, PulseAudio produces "xcb_connection_has_error()"
    // spam and can fail silently. This was a regression introduced when DISPLAY
    // unsetting was removed from the startup path.
    unsetenv("DISPLAY");

    // Start PulseAudio (must be after unsetenv so the subprocess inherits the cleaned env)
    int rc = system("DISPLAY= pulseaudio -D --exit-idle-time=-1 2>/dev/null");
    if (rc != 0) {
        std::cerr << "[System] ERROR: Failed to start PulseAudio." << std::endl;
        std::cerr << "[System] Ensure PulseAudio is installed and you have permission to start it." << std::endl;
        exit(1);
    }

    // Wait for socket to appear at the expected location.
    std::string socket_path = rt_dir + "/pulse/native";
    for (int i = 0; i < 30; i++) {  // 15 second timeout
        struct stat st;
        if (stat(socket_path.c_str(), &st) == 0) {
            std::cout << "[System] PulseAudio started successfully." << std::endl;
            // Set PULSE_SERVER so all subsequent pactl/libpulse calls use the
            // Unix socket directly, bypassing D-Bus X11 connection attempts.
            setenv("PULSE_SERVER", ("unix:" + socket_path).c_str(), 1);
            return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }

    // Last resort: check system-mode socket.
    struct stat st_sys;
    if (stat("/run/pulse/native", &st_sys) == 0) {
        setenv("PULSE_SERVER", "unix:/run/pulse/native", 1);
        std::cout << "[System] PulseAudio started in system mode." << std::endl;
        return;
    }

    std::cerr << "[System] ERROR: PulseAudio started but socket not found." << std::endl;
    std::cerr << "[System] Tried: " << socket_path << std::endl;
    exit(1);
}

// ── PulseAudio Module Cleanup ───────────────────────────────────────────────

/**
 * Unload all AndroidPipe virtual modules from PulseAudio.
 * Called on shutdown to prevent dangling modules from accumulating.
 */
static void cleanup_pulse_modules() {
    std::cout << "[System] Cleaning up PulseAudio virtual modules..." << std::endl;
    FILE* pipe = popen("pactl list short modules", "r");
    if (!pipe) {
        std::cout << "[System] Could not list PulseAudio modules." << std::endl;
        return;
    }
    char buf[512];
    std::vector<int> mod_ids;
    while (fgets(buf, sizeof(buf), pipe)) {
        int id = 0;
        char name[256] = {0};
        if (sscanf(buf, "%d\t%255s", &id, name) == 2) {
            std::string name_str(name);
            // Identify AndroidPipe modules by name or type
            if (name_str.find("AndroidPipe") != std::string::npos ||
                name_str.find("module-null-sink") != std::string::npos ||
                name_str.find("module-remap-source") != std::string::npos) {
                std::cout << "[System] Unloading module " << id << " (" << name_str << ")" << std::endl;
                mod_ids.push_back(id);
            }
        }
    }
    pclose(pipe);

    // Unload modules (reverse order to handle dependencies correctly)
    for (auto it = mod_ids.rbegin(); it != mod_ids.rend(); ++it) {
        std::string cmd = "pactl unload-module " + std::to_string(*it);
        system(cmd.c_str());
    }
    if (!mod_ids.empty()) {
        std::cout << "[System] Cleanup complete (" << mod_ids.size() << " modules removed)." << std::endl;
    } else {
        std::cout << "[System] No AndroidPipe modules found to clean up." << std::endl;
    }
}

// ── Resampler (windowed sinc FIR, 32-tap Hamming) ──────────────────────────

static const int FIR_TAPS = 32;
static const int HALF_TAPS = FIR_TAPS / 2;
static const double CUTOFF_FREQ = 0.48;

static double computeSincCoeff(int n, double ratio) {
    double cutoff = CUTOFF_FREQ / std::max(ratio, 1.0);
    double x = n - HALF_TAPS;
    double window = 0.54 - 0.46 * std::cos(2.0 * M_PI * n / (FIR_TAPS - 1));
    double sinc;
    if (std::abs(x) < 1e-10) {
        sinc = cutoff;
    } else {
        sinc = std::sin(M_PI * cutoff * x) / (M_PI * x);
    }
    return sinc * window / cutoff;
}

static std::vector<int16_t> resample(const std::vector<int16_t>& input, unsigned int srcRate, unsigned int dstRate) {
    if (dstRate == 0 || srcRate == dstRate) return input;
    double ratio = (double)srcRate / dstRate;
    int outputLength = (int)((input.size() + HALF_TAPS) / ratio);
    if (outputLength <= 0) return {};
    std::vector<int16_t> output(outputLength);
    for (int i = 0; i < outputLength; ++i) {
        double position = (i + HALF_TAPS) * ratio - HALF_TAPS;
        int index = (int)std::floor(position);
        if (index < 0) index = 0;
        if (index + FIR_TAPS > (int)input.size()) break;
        double sum = 0.0;
        for (int n = 0; n < FIR_TAPS; ++n) {
            if (index + n >= (int)input.size()) break;
            sum += input[index + n] * computeSincCoeff(n, ratio);
        }
        output[i] = std::max(-32768, std::min(32767, (int)std::round(sum)));
    }
    return output;
}

// ── miniaudio callbacks ────────────────────────────────────────────────────

void data_capture_callback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount) {
    if (null_mode) return;
    const int16_t* input = (const int16_t*)pInput;
    captureBuffer.write(input, frameCount * CHANNELS);
}

void playback_callback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount) {
    int16_t* output = (int16_t*)pOutput;
    size_t read = playbackBuffer.read(output, frameCount * CHANNELS);
    if (read < frameCount * CHANNELS) {
        memset(output + read, 0, (frameCount * CHANNELS - read) * sizeof(int16_t));
    }
}

// ── RT priority (graceful for non-root) ────────────────────────────────────

static void set_rt_priority() {
    struct sched_param param;
    param.sched_priority = 99;
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) != 0) {
        if (is_root()) {
            std::cerr << "Warning: Failed to set SCHED_FIFO priority. Check capabilities." << std::endl;
        } else {
            std::cout << "[System] RT priority not available for non-root user. "
                      << "Set 'realtime-scheduling = yes' in /etc/security/limits.conf "
                      << "or run with 'chrt' after start." << std::endl;
        }
    }
}

// ── Signal pipe (self-pipe trick for breaking out of blocking reads) ──────

static int signal_pipe[2] = {-1, -1};

static void setup_signal_pipe() {
    if (pipe(signal_pipe) < 0) {
        std::cerr << "Warning: Could not create signal pipe." << std::endl;
        signal_pipe[0] = -1;
        signal_pipe[1] = -1;
    }
}

static void drain_signal_pipe() {
    char buf[64];
    while (read(signal_pipe[0], buf, sizeof(buf)) > 0) {}
}

static void close_signal_pipe() {
    if (signal_pipe[0] >= 0) {
        close(signal_pipe[0]);
        signal_pipe[0] = -1;
    }
    if (signal_pipe[1] >= 0) {
        close(signal_pipe[1]);
        signal_pipe[1] = -1;
    }
}

static void signal_handler(int signum) {
    // Write a byte to the pipe to wake up the main thread's poll()
    char c = (char)signum;
    if (signal_pipe[1] >= 0) {
        write(signal_pipe[1], &c, 1);
    }
    running = false;
}

// ── Virtual device setup ───────────────────────────────────────────────────

static void setup_virtual_devices() {
    std::cout << "[System] Provisioning isolated virtual audio devices..." << std::endl;

    if (current_mode == RunMode::MIC || current_mode == RunMode::DUPLEX) {
        std::cout << "[System] Creating Mic path: " << MIC_SOURCE_NAME << std::endl;
        std::string sink_cmd = "pactl load-module module-null-sink sink_name=" + std::string(MIC_SINK_NAME)
            + " sink_properties=device.description='" + std::string(MIC_SINK_DESC) + "'";
        system(sink_cmd.c_str());
        std::string source_cmd = "pactl load-module module-remap-source source_name=" + std::string(MIC_SOURCE_NAME)
            + " source_properties=device.description='" + std::string(MIC_SOURCE_DESC) + "' master=" + std::string(MIC_SINK_NAME) + ".monitor";
        system(source_cmd.c_str());
        system(("pactl set-default-source " + std::string(MIC_SOURCE_NAME)).c_str());
        std::cout << "[System] Set default source to " << MIC_SOURCE_NAME << std::endl;
    }

    if (current_mode == RunMode::SPEAKER || current_mode == RunMode::DUPLEX) {
        std::cout << "[System] Creating Speaker path: " << SPEAKER_SINK_NAME << std::endl;
        std::string sink_cmd = "pactl load-module module-null-sink sink_name=" + std::string(SPEAKER_SINK_NAME)
            + " sink_properties=device.description='" + std::string(SPEAKER_SINK_DESC) + "'";
        system(sink_cmd.c_str());
        std::string source_cmd = "pactl load-module module-remap-source source_name=" + std::string(SPEAKER_SOURCE_NAME)
            + " source_properties=device.description='" + std::string(MIC_SOURCE_DESC) + "' master=" + std::string(SPEAKER_SINK_NAME) + ".monitor";
        system(source_cmd.c_str());
        system(("pactl set-default-sink " + std::string(SPEAKER_SINK_NAME)).c_str());
        std::cout << "[System] Set default sink to " << SPEAKER_SINK_NAME << std::endl;
    }
}

// ── Phone IP management ────────────────────────────────────────────────────

std::string get_phone_ip() {
    std::lock_guard<std::mutex> lock(phoneIpMutex);
    return phoneIp;
}

void set_phone_ip(const std::string& ip) {
    {
        std::lock_guard<std::mutex> lock(phoneIpMutex);
        phoneIp = ip;
    }
    // Wake up outbound thread if it's waiting for an IP
    if (signal_pipe[1] >= 0) {
        write(signal_pipe[1], "I", 1);
    }
}

// Wait until a valid phone IP is set (or running becomes false).
// Returns true if a valid IP was set, false if we should stop.
bool wait_for_phone_ip(const std::string& default_ip) {
    if (!default_ip.empty()) {
        return true; // Use the default if available
    }
    while (running) {
        std::lock_guard<std::mutex> lock(phoneIpMutex);
        if (!phoneIp.empty()) {
            return true;
        }
        phoneIpMutex.unlock();
        // Use poll on signal pipe to avoid busy-spinning
        struct pollfd pf;
        pf.fd = signal_pipe[0];
        pf.events = POLLIN;
        int ret = poll(&pf, 1, 100);
        if (ret > 0) {
            // Drain the signal pipe
            char buf[64];
            while (read(signal_pipe[0], buf, sizeof(buf)) > 0) {}
        }
    }
    return false;
}

// ── Outbound thread (PC -> Phone) ──────────────────────────────────────────

static void* outbound_thread_func(void* arg) {
    (void)arg;
    set_rt_priority();
    std::cout << "[Outbound] Thread started" << std::endl;

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        std::cerr << "[Outbound] Socket creation failed: " << strerror(errno) << std::endl;
        return nullptr;
    }

    struct sockaddr_in phone_addr;
    memset(&phone_addr, 0, sizeof(phone_addr));
    phone_addr.sin_family = AF_INET;
    phone_addr.sin_port = htons(SEND_PORT);
    std::string last_resolved_ip;

    std::vector<int16_t> network_samples(BUFFER_SIZE / sizeof(int16_t));
    std::vector<uint8_t> last_payload;
    uint32_t seq = 0;
    double phase = 0.0;
    const double frequency = 440.0;

    auto next_packet_time = std::chrono::steady_clock::now();

    // Wait until we have a valid phone IP before sending any packets.
    // This prevents the thread from blasting UDP to the wrong IP before the
    // handshake arrives, which causes lost packets and sequence misalignment.
    if (!test_tone_mode && !null_mode) {
        wait_for_phone_ip("");
    }

    while (running) {
        // Dynamically update destination IP if it changes (e.g., handshake arrives late)
        std::string current_ip = get_phone_ip();
        if (!current_ip.empty() && current_ip != last_resolved_ip) {
            inet_pton(AF_INET, current_ip.c_str(), &phone_addr.sin_addr);
            last_resolved_ip = current_ip;
        }
        
        // Skip sending if we don't have a valid destination IP yet.
        // This can happen in race conditions where the IP was cleared (e.g., disconnect).
        if (current_ip.empty() && !test_tone_mode && !null_mode) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }
        unsigned int netRate = current_network_rate.load();
        if (netRate == 0) netRate = HARDWARE_SAMPLE_RATE;
        double phase_increment = 2.0 * M_PI * frequency / HARDWARE_SAMPLE_RATE;
        std::chrono::microseconds packet_interval(1000000LL * (BUFFER_SIZE / sizeof(int16_t)) / netRate);

        if (test_tone_mode) {
            size_t needed_hw_samples = (size_t)((BUFFER_SIZE / sizeof(int16_t)) * ((double)HARDWARE_SAMPLE_RATE / netRate));
            std::vector<int16_t> hw_samples(needed_hw_samples);
            for (auto& s : hw_samples) {
                s = static_cast<int16_t>(16384.0 * std::sin(phase));
                phase += phase_increment;
                if (phase >= 2.0 * M_PI) phase -= 2.0 * M_PI;
            }
            network_samples = resample(hw_samples, HARDWARE_SAMPLE_RATE, netRate);
            if (network_samples.size() > (BUFFER_SIZE / sizeof(int16_t)))
                network_samples.resize(BUFFER_SIZE / sizeof(int16_t));
            std::this_thread::sleep_until(next_packet_time);
            next_packet_time += packet_interval;
        } else if (!null_mode) {
            size_t needed_hw_samples = (size_t)((BUFFER_SIZE / sizeof(int16_t)) * ((double)HARDWARE_SAMPLE_RATE / netRate));
            if (captureBuffer.available_to_read() < needed_hw_samples) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }
            std::vector<int16_t> hw_samples(needed_hw_samples);
            captureBuffer.read(hw_samples.data(), needed_hw_samples);
            network_samples = resample(hw_samples, HARDWARE_SAMPLE_RATE, netRate);
            if (network_samples.size() > (BUFFER_SIZE / sizeof(int16_t)))
                network_samples.resize(BUFFER_SIZE / sizeof(int16_t));
        } else {
            std::fill(network_samples.begin(), network_samples.end(), 0);
            std::this_thread::sleep_until(next_packet_time);
            next_packet_time += packet_interval;
        }

        PacketHeader header;
        header.type = TYPE_AUDIO;
        {
            std::lock_guard<std::mutex> lock(session_mtx);
            memcpy(header.session_id, CURRENT_SESSION, 3);
        }
        header.sequence = htonl(seq++);

        size_t current_payload_size = network_samples.size() * sizeof(int16_t);
        size_t redundant_payload_size = last_payload.size();

        std::vector<uint8_t> packet(sizeof(PacketHeader) + current_payload_size + redundant_payload_size);
        memcpy(packet.data(), &header, sizeof(PacketHeader));
        memcpy(packet.data() + sizeof(PacketHeader), network_samples.data(), current_payload_size);
        if (redundant_payload_size > 0) {
            memcpy(packet.data() + sizeof(PacketHeader) + current_payload_size,
                   last_payload.data(), redundant_payload_size);
        }

        sendto(sock, packet.data(), packet.size(), 0,
               (struct sockaddr*)&phone_addr, sizeof(phone_addr));

        last_payload.assign(
            reinterpret_cast<uint8_t*>(network_samples.data()),
            reinterpret_cast<uint8_t*>(network_samples.data()) + current_payload_size
        );
    }

    close(sock);
    return nullptr;
}

// ── Inbound thread (Phone -> PC) ───────────────────────────────────────────

static void* inbound_thread_func(void* arg) {
    (void)arg;
    set_rt_priority();
    std::cout << "[Inbound] Thread started" << std::endl;

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in my_addr;
    memset(&my_addr, 0, sizeof(my_addr));
    my_addr.sin_family = AF_INET;
    my_addr.sin_port = htons(RECV_PORT);
    my_addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock, (struct sockaddr*)&my_addr, sizeof(my_addr)) < 0) {
        std::cerr << "[Inbound] Bind error: " << strerror(errno) << std::endl;
        return nullptr;
    }

    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 500000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    std::vector<uint8_t> buffer(8 + (2 * BUFFER_SIZE) + 128);
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 255);

    while (running) {
        struct sockaddr_in from_addr;
        socklen_t from_len = sizeof(from_addr);
        ssize_t len = recvfrom(sock, buffer.data(), buffer.size(), 0,
                               (struct sockaddr*)&from_addr, &from_len);

        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
            if (running) std::cerr << "[Inbound] recvfrom error: " << strerror(errno) << std::endl;
            continue;
        }

        if (len < (ssize_t)sizeof(PacketHeader)) continue;

        PacketHeader header;
        std::memcpy(&header, buffer.data(), sizeof(PacketHeader));

        if (header.type == TYPE_HANDSHAKE_REQ) {
            std::cout << "\n[Inbound] Handshake request from " << inet_ntoa(from_addr.sin_addr)
                      << ". Assigning session..." << std::endl;
            set_phone_ip(inet_ntoa(from_addr.sin_addr));
            PacketHeader resp;
            resp.type = TYPE_HANDSHAKE_RESP;
            {
                std::lock_guard<std::mutex> lock(session_mtx);
                if (CURRENT_SESSION[0] == 0 && CURRENT_SESSION[1] == 0 && CURRENT_SESSION[2] == 0) {
                    CURRENT_SESSION[0] = dis(gen);
                    CURRENT_SESSION[1] = dis(gen);
                    CURRENT_SESSION[2] = dis(gen);
                }
                memcpy(resp.session_id, CURRENT_SESSION, 3);
            }
            resp.sequence = 0;
            sendto(sock, &resp, sizeof(PacketHeader), 0,
                   (struct sockaddr*)&from_addr, from_len);
            continue;
        }

        bool sessionMatch = true;
        {
            std::lock_guard<std::mutex> lock(session_mtx);
            for (int i = 0; i < 3; i++) {
                if (header.session_id[i] != CURRENT_SESSION[i]) {
                    sessionMatch = false;
                    break;
                }
            }
        }
        if (!sessionMatch) continue;

        if (header.type == TYPE_DISCONNECT) {
            std::cout << "\n[Inbound] Client disconnected explicitly." << std::endl;
            stats.connected = false;
            {
                std::lock_guard<std::mutex> lock(session_mtx);
                memset(CURRENT_SESSION, 0, 3);
            }
            continue;
        }

        auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        stats.last_seen_ms = now_ms;
        stats.connected = true;

        if (header.type == TYPE_AUDIO) {
            uint32_t seq = ntohl(header.sequence);

            if (!firstPacketReceived) {
                nextExpectedSeq = seq;
                firstPacketReceived = true;
            }

            int lost = 0;
            if (stats.last_sequence > 0 && seq > stats.last_sequence + 1) {
                lost = seq - stats.last_sequence - 1;
                stats.packets_lost += lost;
            }
            stats.last_sequence = seq;
            stats.packets_received++;
            stats.record_loss(lost);

            if (!null_mode) {
                size_t available_payload = len - sizeof(PacketHeader);
                if (available_payload == 0) continue;

                size_t current_payload_len = std::min(available_payload, BUFFER_SIZE);
                uint8_t* audio_ptr = buffer.data() + sizeof(PacketHeader);

                int16_t* samples_raw = reinterpret_cast<int16_t*>(audio_ptr);
                int num_samples = current_payload_len / sizeof(int16_t);
                std::vector<int16_t> net_samples(num_samples);
                for (int i = 0; i < num_samples; ++i) {
                    net_samples[i] = samples_raw[i];
                }

                unsigned int netRate = current_network_rate.load();
                std::vector<int16_t> hw_samples = resample(net_samples, netRate, HARDWARE_SAMPLE_RATE);

                {
                    std::lock_guard<std::mutex> lock(jitterMtx);
                    jitterBuffer.push({seq, hw_samples});
                }

                // FEC: recover missing packet from redundant data
                size_t redundant_offset = sizeof(PacketHeader) + current_payload_len;
                if (redundant_offset < available_payload) {
                    size_t red_len = std::min(available_payload - redundant_offset, BUFFER_SIZE);
                    uint8_t* red_ptr = buffer.data() + redundant_offset;
                    int16_t* red_samples_raw = reinterpret_cast<int16_t*>(red_ptr);
                    int red_num_samples = red_len / sizeof(int16_t);

                    if (seq > nextExpectedSeq && nextExpectedSeq != 0) {
                        std::vector<int16_t> red_net_samples(red_num_samples);
                        for (int i = 0; i < red_num_samples; ++i)
                            red_net_samples[i] = red_samples_raw[i];
                        std::vector<int16_t> recovered_samples = resample(red_net_samples, netRate, HARDWARE_SAMPLE_RATE);
                        std::lock_guard<std::mutex> lock(jitterMtx);
                        jitterBuffer.push({nextExpectedSeq, recovered_samples});
                    }
                }
            }
        } else if (header.type == TYPE_PING) {
            PacketHeader pong;
            pong.type = TYPE_PONG;
            {
                std::lock_guard<std::mutex> lock(session_mtx);
                memcpy(pong.session_id, CURRENT_SESSION, 3);
            }
            pong.sequence = 0;
            sendto(sock, &pong, sizeof(PacketHeader), 0,
                   (struct sockaddr*)&from_addr, from_len);
        } else if (header.type == TYPE_NEGOTIATE) {
            if (len >= (ssize_t)(sizeof(PacketHeader) + 4)) {
                uint32_t requested_rate;
                memcpy(&requested_rate, buffer.data() + sizeof(PacketHeader), 4);
                requested_rate = ntohl(requested_rate);

                if (requested_rate == 0 || requested_rate > 192000) {
                    std::cerr << "\n[Inbound] Rejected invalid negotiation request: "
                              << requested_rate << "Hz" << std::endl;
                    continue;
                }

                std::cout << "\n[Inbound] Negotiation request: " << requested_rate
                          << "Hz. Updating server..." << std::endl;
                current_network_rate = requested_rate;

                PacketHeader resp;
                resp.type = TYPE_NEGOTIATE;
                {
                    std::lock_guard<std::mutex> lock(session_mtx);
                    memcpy(resp.session_id, CURRENT_SESSION, 3);
                }
                resp.sequence = 0;
                uint32_t rate_to_send = htonl(requested_rate);
                std::vector<uint8_t> packet(sizeof(PacketHeader) + 4);
                memcpy(packet.data(), &resp, sizeof(PacketHeader));
                memcpy(packet.data() + sizeof(PacketHeader), &rate_to_send, 4);
                sendto(sock, packet.data(), packet.size(), 0,
                       (struct sockaddr*)&from_addr, from_len);
            }
        }
    }

    close(sock);
    return nullptr;
}

// ── Jitter drain thread ────────────────────────────────────────────────────

static void* jitter_drain_thread_func(void* arg) {
    (void)arg;
    set_rt_priority();
    std::cout << "[JitterDrain] Thread started" << std::endl;

    while (running) {
        std::vector<int16_t> samples_to_play;
        {
            std::lock_guard<std::mutex> lock(jitterMtx);
            if (!jitterBuffer.empty()) {
                size_t current_threshold = adaptive_jitter_threshold.load();

                if (jitterBuffer.size() > DRIFT_THRESHOLD) {
                    // Only increase threshold on OVERFLOW to prevent stale packets.
                    // During underflow (common at startup), lowering the threshold
                    // is correct — we want to play packets ASAP to minimize latency.
                    // BUG #3 FIX: Removed threshold increase on underflow (< 2 packets).
                    // Increasing threshold when buffer is low causes initial latency
                    // spikes because the server holds packets longer than necessary.
                    while (jitterBuffer.size() > current_threshold) jitterBuffer.pop();
                    if (!jitterBuffer.empty()) nextExpectedSeq = jitterBuffer.top().sequence;
                    if (current_threshold < JITTER_THRESHOLD_MAX) adaptive_jitter_threshold++;
                }
                // Removed: else if (jitterBuffer.size() < 2) {
                //     if (current_threshold < JITTER_THRESHOLD_MAX) adaptive_jitter_threshold++;
                // }
            }
        }

        {
            std::lock_guard<std::mutex> lock(jitterMtx);
            if (!jitterBuffer.empty() && jitterBuffer.size() >= adaptive_jitter_threshold.load()) {
                AudioPacket p = jitterBuffer.top();
                if (p.sequence == nextExpectedSeq) {
                    samples_to_play = p.samples;
                    nextExpectedSeq++;
                    jitterBuffer.pop();
                } else if (p.sequence < nextExpectedSeq) {
                    jitterBuffer.pop();
                } else {
                    if (jitterBuffer.size() > MAX_JITTER_SIZE) {
                        samples_to_play = p.samples;
                        nextExpectedSeq = p.sequence + 1;
                        jitterBuffer.pop();
                    }
                }
            }
        }

        if (!samples_to_play.empty()) {
            playbackBuffer.write(samples_to_play.data(), samples_to_play.size());
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }

    return nullptr;
}

// ── Monitor thread ─────────────────────────────────────────────────────────

static void* monitor_thread_func(void* arg) {
    (void)arg;
    while (running) {
        auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        long long last_seen = stats.last_seen_ms;
        long long diff = (last_seen == 0) ? -1 : (now_ms - last_seen);

        const char* mode_str = (current_mode == RunMode::MIC ? "MIC" :
                                current_mode == RunMode::SPEAKER ? "SPEAKER" : "DUPLEX");

        std::cout << "\r[Status] "
                  << (diff != -1 && diff < 3000 ? "CONNECTED \xe2\x9c\x85" : "DISCONNECTED \xe2\x9c\x8b")
                  << " | Mode: " << mode_str
                  << " | NetRate: " << current_network_rate.load() << "Hz"
                  << " | Pkts: " << stats.packets_received
                  << " | Loss: " << std::fixed << std::setprecision(2) << stats.get_rolling_loss_pct() << "%"
                  << " | Latency: " << (diff == -1 || diff >= 3000 ? "N/A" : std::to_string(diff) + "ms")
                  << " " << std::flush;

        if (diff >= 3000) stats.connected = false;
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    std::cout << std::endl;
    return nullptr;
}

// ── TCP client thread (phone control) ──────────────────────────────────────

static void* tcp_client_thread_func(void* arg) {
    (void)arg;
    std::cout << "[TCP Client] Thread started, connecting to phone on port "
              << PHONE_TCP_CONTROL_PORT << std::endl;

    while (running) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) {
            std::cerr << "[TCP Client] Socket creation failed: " << strerror(errno) << std::endl;
            std::this_thread::sleep_for(std::chrono::milliseconds(TCP_RECONNECT_INTERVAL_MS));
            continue;
        }

        struct sockaddr_in phone_addr;
        memset(&phone_addr, 0, sizeof(phone_addr));
        phone_addr.sin_family = AF_INET;
        phone_addr.sin_port = htons(PHONE_TCP_CONTROL_PORT);
        inet_pton(AF_INET, get_phone_ip().c_str(), &phone_addr.sin_addr);

        std::cout << "[TCP Client] Connecting to " << get_phone_ip() << ":"
                  << PHONE_TCP_CONTROL_PORT << std::endl;

        if (connect(sock, (struct sockaddr*)&phone_addr, sizeof(phone_addr)) < 0) {
            std::cerr << "[TCP Client] Connect failed: " << strerror(errno)
                      << ". Retrying in " << TCP_RECONNECT_INTERVAL_MS << "ms..." << std::endl;
            close(sock);
            std::this_thread::sleep_for(std::chrono::milliseconds(TCP_RECONNECT_INTERVAL_MS));
            continue;
        }

        std::cout << "[TCP Client] Connected to phone TCP control server!" << std::endl;

        // Set a short receive timeout so recv() unblocks quickly on shutdown
        struct timeval tv_tcp;
        tv_tcp.tv_sec = 0;
        tv_tcp.tv_usec = 100000;  // 100ms timeout
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv_tcp, sizeof(tv_tcp));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv_tcp, sizeof(tv_tcp));

        // Track socket fd so main() can close it to unblock any pending recv()
        g_tcp_sock.store(sock);

        // Send initial sample rate negotiation
        std::string rate_cmd = "SET_SAMPLE_RATE " + std::to_string(current_network_rate.load());
        const char* cmd_buf = rate_cmd.c_str();
        ssize_t sent = send(sock, cmd_buf, strlen(cmd_buf), 0);
        if (sent > 0) {
            std::cout << "[TCP Client] Sent: " << cmd_buf << std::endl;
            char resp[256];
            ssize_t resp_len = recv(sock, resp, sizeof(resp) - 1, 0);
            if (resp_len > 0) {
                resp[resp_len] = '\0';
                std::cout << "[TCP Client] Response: " << resp << std::endl;
            } else if (resp_len < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                std::cerr << "[TCP Client] Initial receive failed: " << strerror(errno) << std::endl;
            }
        }

        while (running) {
            std::string ping_cmd = "PING";
            ssize_t sent = send(sock, ping_cmd.c_str(), ping_cmd.length(), 0);
            if (sent < 0) {
                std::cerr << "[TCP Client] Send failed: " << strerror(errno) << std::endl;
                break;
            }

            char resp[256];
            ssize_t resp_len = recv(sock, resp, sizeof(resp) - 1, 0);
            if (resp_len < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    // Timeout — check running flag and loop back quickly
                    continue;
                }
                std::cerr << "[TCP Client] Receive failed: " << strerror(errno) << std::endl;
                break;
            } else if (resp_len == 0) {
                std::cout << "[TCP Client] Connection closed by phone." << std::endl;
                break;
            }
            resp[resp_len] = '\0';
            std::cout << "[TCP Client] Pong: " << resp << std::endl;

            std::this_thread::sleep_for(std::chrono::seconds(5));
        }

        g_tcp_sock.store(-1);  // Release socket fd before closing
        close(sock);
        std::cout << "[TCP Client] Disconnecting, will reconnect in "
                  << TCP_RECONNECT_INTERVAL_MS << "ms..." << std::endl;
        std::this_thread::sleep_for(std::chrono::milliseconds(TCP_RECONNECT_INTERVAL_MS));
    }

    std::cout << "[TCP Client] Thread stopped." << std::endl;
    return nullptr;
}

// ── Main ───────────────────────────────────────────────────────────────────

int main(int argc, char** argv) {
    // Parse arguments
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--help" || arg == "-h") {
            std::cout << "Usage: audio_pipe_user [OPTIONS]\n"
                      << "\n"
                      << "Options:\n"
                      << "  --help, -h        Show this help message\n"
                      << "  --null            Bypass audio hardware (null mode)\n"
                      << "  --test-tone       Generate 440Hz test tone\n"
                      << "  --mode MIC|SPEAKER|DUPLEX  Audio direction (default: DUPLEX)\n"
                      << "\n"
                      << "Modes:\n"
                      << "  MIC      Phone -> PC only (phone acts as microphone)\n"
                      << "  SPEAKER  PC -> Phone only (phone acts as speaker)\n"
                      << "  DUPLEX   Both directions (default)\n"
                      << std::endl;
            return 0;
        }
        if (arg == "--null") null_mode = true;
        if (arg == "--test-tone") test_tone_mode = true;
        if (arg == "--mode") {
            if (i + 1 < argc) {
                std::string m = argv[++i];
                if (m == "mic") current_mode = RunMode::MIC;
                else if (m == "speaker") current_mode = RunMode::SPEAKER;
                else if (m == "duplex") current_mode = RunMode::DUPLEX;
                else std::cerr << "Unknown mode: " << m << ". Using duplex." << std::endl;
            }
        }
    }

    // Signal handling
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGQUIT, signal_handler);

    // Set up self-pipe for signal interruption (MUST be called before poll)
    setup_signal_pipe();

    // User mode banner
    std::cout << "=============================================" << std::endl;
    std::cout << " Android Audio Pipe — User Mode Server" << std::endl;
    std::cout << "=============================================" << std::endl;
    std::cout << "UID: " << get_current_uid()
              << " (" << (is_root() ? "root" : "standard user") << ")" << std::endl;
    std::cout << "Runtime dir: " << get_runtime_dir() << std::endl;
    std::cout << "=============================================" << std::endl;

    if (null_mode) std::cout << "!!! RUNNING IN NULL-AUDIO MODE (Bypassing Audio Hardware) !!!" << std::endl;
    if (test_tone_mode) std::cout << "!!! TEST TONE MODE ACTIVE: Generating 440Hz Sine Wave !!!" << std::endl;

    // Ensure PulseAudio is available
    ensure_pulseaudio_running();

    // Setup virtual devices
    if (!null_mode) {
        setup_virtual_devices();

        if (current_mode == RunMode::MIC || current_mode == RunMode::DUPLEX) {
            std::string src = std::string(MIC_SINK_NAME) + ".monitor";
            setenv("PULSE_SOURCE", src.c_str(), 1);
        }
        if (current_mode == RunMode::SPEAKER || current_mode == RunMode::DUPLEX) {
            std::string sink = std::string(SPEAKER_SINK_NAME);
            setenv("PULSE_SINK", sink.c_str(), 1);
        }
    }

    std::cout << "[System] Mode: "
              << (current_mode == RunMode::MIC ? "MIC" :
                  current_mode == RunMode::SPEAKER ? "SPEAKER" : "DUPLEX")
              << std::endl;

    // miniaudio capture device
    ma_device_config captureConfig = ma_device_config_init(ma_device_type_capture);
    captureConfig.capture.format = ma_format_s16;
    captureConfig.capture.channels = CHANNELS;
    captureConfig.sampleRate = HARDWARE_SAMPLE_RATE;
    captureConfig.dataCallback = data_capture_callback;

    ma_device captureDevice;
    bool capture_started = false;
    if (current_mode != RunMode::MIC && !null_mode) {
        if (ma_device_init(NULL, &captureConfig, &captureDevice) == MA_SUCCESS) {
            ma_device_start(&captureDevice);
            capture_started = true;
        } else {
            std::cerr << "Failed to initialize capture device" << std::endl;
            return 1;
        }
    } else {
        std::cout << "[System] Capture disabled (Mode: "
                  << (current_mode == RunMode::MIC ? "MIC" : "NULL") << ")." << std::endl;
    }

    // miniaudio playback device
    ma_device_config playbackConfig = ma_device_config_init(ma_device_type_playback);
    playbackConfig.playback.format = ma_format_s16;
    playbackConfig.playback.channels = CHANNELS;
    playbackConfig.sampleRate = HARDWARE_SAMPLE_RATE;
    playbackConfig.dataCallback = playback_callback;

    ma_device playbackDevice;
    bool playback_started = false;
    if (current_mode != RunMode::SPEAKER && !null_mode) {
        if (ma_device_init(NULL, &playbackConfig, &playbackDevice) == MA_SUCCESS) {
            ma_device_start(&playbackDevice);
            playback_started = true;
        } else {
            std::cerr << "Failed to initialize playback device" << std::endl;
            return 1;
        }
    } else {
        std::cout << "[System] Playback disabled (Mode: "
                  << (current_mode == RunMode::SPEAKER ? "SPEAKER" : "NULL") << ")." << std::endl;
    }

    std::cout << "PC Capture (to Phone): " << (null_mode ? "NULL" : std::string(MIC_SOURCE_NAME))
              << " -> " << get_phone_ip() << ":" << SEND_PORT << std::endl;
    std::cout << "Phone (to PC): " << RECV_PORT << " -> PC Playback: "
              << (null_mode ? "NULL" : std::string(MIC_SINK_NAME)) << std::endl;

    // Launch threads using pthreads
    pthread_t t_out, t_in, t_drain, t_mon, t_tcp;

    pthread_create(&t_out, NULL, outbound_thread_func, NULL);
    pthread_create(&t_in, NULL, inbound_thread_func, NULL);
    pthread_create(&t_drain, NULL, jitter_drain_thread_func, NULL);
    pthread_create(&t_mon, NULL, monitor_thread_func, NULL);
    pthread_create(&t_tcp, NULL, tcp_client_thread_func, NULL);

    std::cout << "Server running. Press Enter or Ctrl+C to stop." << std::endl;

    // Use poll() on stdin + signal pipe to handle both Enter and Ctrl+C
    struct pollfd pfd[2];
    pfd[0].fd = STDIN_FILENO;
    pfd[0].events = POLLIN;
    pfd[1].fd = signal_pipe[0];
    pfd[1].events = POLLIN;

    // Block until Enter is pressed or a signal arrives
    // Retry if poll() is interrupted by a signal (EINTR)
    int poll_ret;
    do {
        poll_ret = poll(pfd, 2, -1);
    } while (poll_ret < 0 && errno == EINTR);
    
    if (poll_ret > 0) {
        if (pfd[1].revents & POLLIN) {
            // Signal or IPC message was received — drain ALL bytes from the pipe.
            // Signal handler writes the signal number (2, 3, or 15) which are
            // non-printable. IPC set_phone_ip() writes "I" (0x49).
            // We only exit on real signals, not on IPC messages.
            char buf[64];
            ssize_t n;
            bool got_signal = false;
            while ((n = read(signal_pipe[0], buf, sizeof(buf))) > 0) {
                for (ssize_t i = 0; i < n; i++) {
                    if (buf[i] == SIGINT || buf[i] == SIGTERM || buf[i] == SIGQUIT) {
                        got_signal = true;
                    }
                }
            }
            if (got_signal) {
                std::cout << "\n[System] Signal received. Shutting down..." << std::endl;
            }
            // If got_signal is false, this was just an IPC "I" from set_phone_ip();
            // don't exit — just continue polling.
        }
        if (pfd[0].revents & POLLIN) {
            // Enter was pressed — read ONE byte to clear stdin
            char buf[1];
            // Set stdin to non-blocking temporarily
            int flags = fcntl(STDIN_FILENO, F_GETFL, 0);
            fcntl(STDIN_FILENO, F_SETFL, flags | O_NONBLOCK);
            read(STDIN_FILENO, buf, 1);
            fcntl(STDIN_FILENO, F_SETFL, flags);
        }
    }

    // Signal threads to stop
    running = false;

    // Unblock TCP client thread if it's stuck in recv()
    int tcp_fd = g_tcp_sock.exchange(-1);
    if (tcp_fd >= 0) {
        std::cout << "[System] Closing TCP client socket to unblock shutdown..." << std::endl;
        close(tcp_fd);
    }

    // Wait for all threads to finish
    pthread_join(t_out, NULL);
    pthread_join(t_in, NULL);
    pthread_join(t_drain, NULL);
    pthread_join(t_mon, NULL);
    pthread_join(t_tcp, NULL);

    // Uninitialize audio devices
    if (capture_started) ma_device_uninit(&captureDevice);
    if (playback_started) ma_device_uninit(&playbackDevice);

    // Clean up PulseAudio virtual modules
    cleanup_pulse_modules();

    // Close the signal pipe
    close_signal_pipe();

    return 0;
}
