#define MINIAUDIO_IMPLEMENTATION
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
#include <pthread.h>
#include <unistd.h>
#include <iomanip>
#include <chrono>
#include <deque>
#include <numeric>
#include <mutex>
#include <cmath>
#include <random>
#include <cstdlib>
#include <queue>

// Configuration
const char* PHONE_IP = "192.168.168.120"; 
const int RECV_PORT = 12345; 
const int SEND_PORT = 12346; 
std::atomic<unsigned int> current_sample_rate{44100};
const unsigned int CHANNELS = 1; // Match Android App (Mono)
const size_t BUFFER_SIZE = 512;
const float INBOUND_GAIN = 2.0f;

enum class RunMode {
    MIC,      // Phone -> PC (Phone acts as Mic)
    SPEAKER,  // PC -> Phone (Phone acts as Speaker)
    DUPLEX    // Both
};
RunMode current_mode = RunMode::DUPLEX;

// Virtual Device Names for PulseAudio
const char* MIC_SINK_NAME = "AndroidPipe_MicSink";
const char* MIC_SOURCE_NAME = "AndroidPipe_Mic";
const char* MIC_SINK_DESC = "Android_Mic_Internal";
const char* MIC_SOURCE_DESC = "Android_Phone_Microphone";

const char* SPEAKER_SINK_NAME = "AndroidPipe_Speaker";
const char* SPEAKER_SOURCE_NAME = "AndroidPipe_SpeakerMonitor";
const char* SPEAKER_SINK_DESC = "Android_Phone_Speaker";
const char* SPEAKER_SOURCE_DESC = "Android_Speaker_Internal";

enum PacketType : uint8_t {
    TYPE_AUDIO = 0x01,
    TYPE_PING = 0x02,
    TYPE_PONG = 0x03,
    TYPE_STATS = 0x04,
    TYPE_HANDSHAKE_REQ = 0x05,
    TYPE_HANDSHAKE_RESP = 0x06,
    TYPE_NEGOTIATE = 0x07,
    TYPE_DISCONNECT = 0x08
};

#pragma pack(push, 1)
struct PacketHeader {
    uint8_t type;
    uint8_t session_id[3];
    uint32_t sequence;
};
#pragma pack(pop)

struct ConnectionStats {
    std::atomic<uint64_t> packets_received{0};
    std::atomic<uint64_t> packets_lost{0};
    std::atomic<uint32_t> last_sequence{0};
    std::atomic<long long> last_seen_ms{0};
    std::atomic<bool> connected{false};
    
    std::deque<int> loss_window;
    std::mutex window_mtx;
    const size_t WINDOW_SIZE = 100;

    void record_loss(int lost_count) {
        std::lock_guard<std::mutex> lock(window_mtx);
        loss_window.push_back(lost_count);
        if (loss_window.size() > WINDOW_SIZE) {
            loss_window.pop_front();
        }
    }

    double get_rolling_loss_pct() {
        std::lock_guard<std::mutex> lock(window_mtx);
        if (loss_window.empty()) return 0.0;
        long long total_lost = std::accumulate(loss_window.begin(), loss_window.end(), 0LL);
        return (double)total_lost / (double)WINDOW_SIZE * 100.0;
    }
};

template <typename T>
class AudioRingBuffer {
    std::vector<T> buffer;
    size_t head = 0;
    size_t tail = 0;
    size_t size = 0;
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

struct AudioPacket {
    uint32_t sequence;
    std::vector<int16_t> samples;
    bool operator>(const AudioPacket& other) const { return sequence > other.sequence; }
};

std::atomic<bool> running(true);
std::atomic<bool> null_mode(false);
std::atomic<bool> test_tone_mode(false);
ConnectionStats stats;
uint8_t CURRENT_SESSION[3] = {0x00, 0x00, 0x00};
std::mutex session_mtx;

AudioRingBuffer<int16_t> captureBuffer(192000);
AudioRingBuffer<int16_t> playbackBuffer(192000);

std::priority_queue<AudioPacket, std::vector<AudioPacket>, std::greater<AudioPacket>> jitterBuffer;
std::mutex jitterMtx;
uint32_t nextExpectedSeq = 0;
bool firstPacketReceived = false;
const size_t JITTER_THRESHOLD_MIN = 3;
const size_t JITTER_THRESHOLD_MAX = 20;
const size_t DRIFT_THRESHOLD = 30;
const size_t MAX_JITTER_SIZE = 50;

std::atomic<size_t> adaptive_jitter_threshold{5};

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

void set_rt_priority() {
    struct sched_param param;
    param.sched_priority = 99;
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) != 0) {
        std::cerr << "Warning: Failed to set SCHED_FIFO priority. Run as root!" << std::endl;
    }
}

void outbound_thread() {
    set_rt_priority();
    std::cout << "[Outbound] Thread started" << std::endl;

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in phone_addr;
    memset(&phone_addr, 0, sizeof(phone_addr));
    phone_addr.sin_family = AF_INET;
    phone_addr.sin_port = htons(SEND_PORT);
    inet_pton(AF_INET, PHONE_IP, &phone_addr.sin_addr);

    std::vector<int16_t> audio_samples(BUFFER_SIZE / sizeof(int16_t));
    std::vector<uint8_t> last_payload;
    uint32_t seq = 0;
    double phase = 0.0;
    const double frequency = 440.0;

    auto next_packet_time = std::chrono::steady_clock::now();

    while (running) {
        unsigned int rate = current_sample_rate.load();
        double phase_increment = 2.0 * M_PI * frequency / rate;
        std::chrono::microseconds packet_interval(1000000LL * (BUFFER_SIZE / sizeof(int16_t)) / rate);

        if (test_tone_mode) {
            for (auto& s : audio_samples) {
                s = static_cast<int16_t>(16384.0 * std::sin(phase));
                phase += phase_increment;
                if (phase >= 2.0 * M_PI) phase -= 2.0 * M_PI;
            }
            std::this_thread::sleep_until(next_packet_time);
            next_packet_time += packet_interval;
        } else if (!null_mode) {
            if (captureBuffer.available_to_read() < audio_samples.size()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }
            captureBuffer.read(audio_samples.data(), audio_samples.size());
        } else {
            std::fill(audio_samples.begin(), audio_samples.end(), 0);
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

        size_t current_payload_size = audio_samples.size() * sizeof(int16_t);
        size_t redundant_payload_size = last_payload.size();

        std::vector<uint8_t> packet(sizeof(PacketHeader) + current_payload_size + redundant_payload_size);
        memcpy(packet.data(), &header, sizeof(PacketHeader));
        memcpy(packet.data() + sizeof(PacketHeader), audio_samples.data(), current_payload_size);
        if (redundant_payload_size > 0) {
            memcpy(packet.data() + sizeof(PacketHeader) + current_payload_size, last_payload.data(), redundant_payload_size);
        }

        sendto(sock, packet.data(), packet.size(), 0, (struct sockaddr*)&phone_addr, sizeof(phone_addr));
        
        last_payload.assign(
            reinterpret_cast<uint8_t*>(audio_samples.data()), 
            reinterpret_cast<uint8_t*>(audio_samples.data()) + current_payload_size
        );
    }

    close(sock);
}

void inbound_thread() {
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
        return;
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
        ssize_t len = recvfrom(sock, buffer.data(), buffer.size(), 0, (struct sockaddr*)&from_addr, &from_len);
        
        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
            if (running) std::cerr << "[Inbound] recvfrom error: " << strerror(errno) << std::endl;
            continue;
        }
        
        if (len < (ssize_t)sizeof(PacketHeader)) continue;

        PacketHeader header;
        std::memcpy(&header, buffer.data(), sizeof(PacketHeader));
        
        if (header.type == TYPE_HANDSHAKE_REQ) {
            std::cout << "\n[Inbound] Handshake request from " << inet_ntoa(from_addr.sin_addr) << ". Assigning session..." << std::endl;
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
            sendto(sock, &resp, sizeof(PacketHeader), 0, (struct sockaddr*)&from_addr, from_len);
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
                std::vector<int16_t> current_samples(num_samples);
                
                for (int i = 0; i < num_samples; ++i) {
                    float sample = samples_raw[i] * INBOUND_GAIN;
                    if (sample > 32767.0f) sample = 32767.0f;
                    if (sample < -32768.0f) sample = -32768.0f;
                    current_samples[i] = static_cast<int16_t>(sample);
                }

                size_t redundant_offset = sizeof(PacketHeader) + current_payload_len;
                if (redundant_offset < available_payload) {
                    size_t red_len = std::min(available_payload - redundant_offset, BUFFER_SIZE);
                    uint8_t* red_ptr = buffer.data() + redundant_offset;
                    int16_t* red_samples_raw = reinterpret_cast<int16_t*>(red_ptr);
                    int red_num_samples = red_len / sizeof(int16_t);
                    
                if (seq > nextExpectedSeq && nextExpectedSeq != 0) {
                    std::vector<int16_t> recovered_samples(red_num_samples);
                    for(int i=0; i<red_num_samples; ++i) {
                        float s = red_samples_raw[i] * INBOUND_GAIN;
                        if (s > 32767.0f) s = 32767.0f;
                        if (s < -32768.0f) s = -32768.0f;
                        recovered_samples[i] = static_cast<int16_t>(s);
                    }
                    
                    std::lock_guard<std::mutex> lock(jitterMtx);
                    jitterBuffer.push({nextExpectedSeq, recovered_samples});
                }
                }

                {
                    std::lock_guard<std::mutex> lock(jitterMtx);
                    jitterBuffer.push({seq, current_samples});
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
            sendto(sock, &pong, sizeof(PacketHeader), 0, (struct sockaddr*)&from_addr, from_len);
        } else if (header.type == TYPE_NEGOTIATE) {
            if (len >= (ssize_t)(sizeof(PacketHeader) + 4)) {
                uint32_t requested_rate;
                memcpy(&requested_rate, buffer.data() + sizeof(PacketHeader), 4);
                requested_rate = ntohl(requested_rate);
                
                std::cout << "\n[Inbound] Negotiation request: " << requested_rate << "Hz. Updating server..." << std::endl;
                
                current_sample_rate = requested_rate;
                
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
                sendto(sock, packet.data(), packet.size(), 0, (struct sockaddr*)&from_addr, from_len);
            }
        }
    }

    close(sock);
}

void jitter_drain_thread() {
    set_rt_priority();
    std::cout << "[JitterDrain] Thread started" << std::endl;
    
    while (running) {
        std::vector<int16_t> samples_to_play;
        {
            std::lock_guard<std::mutex> lock(jitterMtx);
            if (!jitterBuffer.empty()) {
                size_t current_threshold = adaptive_jitter_threshold.load();
                
                if (jitterBuffer.size() > DRIFT_THRESHOLD) {
                    while(jitterBuffer.size() > current_threshold) {
                        jitterBuffer.pop();
                    }
                    if (!jitterBuffer.empty()) {
                        nextExpectedSeq = jitterBuffer.top().sequence;
                    }
                    if (current_threshold < JITTER_THRESHOLD_MAX) {
                        adaptive_jitter_threshold++;
                    }
                } else if (jitterBuffer.size() < 2) {
                    if (current_threshold < JITTER_THRESHOLD_MAX) {
                        adaptive_jitter_threshold++;
                    }
                }
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
}

void monitor_thread() {
    while (running) {
        auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        long long last_seen = stats.last_seen_ms;
        long long diff = (last_seen == 0) ? -1 : (now_ms - last_seen);
        
        std::cout << "\r" << "[Status] " 
                  << (diff != -1 && diff < 3000 ? "CONNECTED ✅" : "DISCONNECTED ❌") 
                  << " | Rate: " << current_sample_rate.load() << "Hz"
                  << " | Pkts: " << stats.packets_received 
                  << " | Rolling Loss: " << std::fixed << std::setprecision(2) << stats.get_rolling_loss_pct() << "%"
                  << " | Latency: " << (diff == -1 || diff >= 3000 ? "N/A" : std::to_string(diff) + "ms") << " " << std::flush;
        
        if (diff >= 3000) stats.connected = false;
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    std::cout << std::endl;
}

void setup_virtual_devices() {
    std::cout << "[System] Provisioning isolated virtual audio devices..." << std::endl;
    
    if (system("pgrep pulseaudio > /dev/null") != 0) {
        std::cout << "[System] PulseAudio not detected. Attempting to start..." << std::endl;
        system("pulseaudio -D --exit-idle-time=-1 2>/dev/null");
        sleep(2);
    }

    if (current_mode == RunMode::MIC || current_mode == RunMode::DUPLEX) {
        std::cout << "[System] Creating Mic path: " << MIC_SOURCE_NAME << std::endl;
        std::string sink_cmd = "pactl load-module module-null-sink sink_name=" + std::string(MIC_SINK_NAME) + " sink_properties=device.description='" + std::string(MIC_SINK_DESC) + "'";
        system(sink_cmd.c_str());
        std::string source_cmd = "pactl load-module module-remap-source source_name=" + std::string(MIC_SOURCE_NAME) + " source_properties=device.description='" + std::string(MIC_SOURCE_DESC) + "' master=" + std::string(MIC_SINK_NAME) + ".monitor";
        system(source_cmd.c_str());
        
        system(("pactl set-default-source " + std::string(MIC_SOURCE_NAME)).c_str());
        std::cout << "[System] Set default source to " << MIC_SOURCE_NAME << std::endl;
    }

    if (current_mode == RunMode::SPEAKER || current_mode == RunMode::DUPLEX) {
        std::cout << "[System] Creating Speaker path: " << SPEAKER_SINK_NAME << std::endl;
        std::string sink_cmd = "pactl load-module module-null-sink sink_name=" + std::string(SPEAKER_SINK_NAME) + " sink_properties=device.description='" + std::string(SPEAKER_SINK_DESC) + "'";
        system(sink_cmd.c_str());
        std::string source_cmd = "pactl load-module module-remap-source source_name=" + std::string(SPEAKER_SOURCE_NAME) + " source_properties=device.description='" + std::string(SPEAKER_SOURCE_DESC) + "' master=" + std::string(SPEAKER_SINK_NAME) + ".monitor";
        system(source_cmd.c_str());
        
        system(("pactl set-default-sink " + std::string(SPEAKER_SINK_NAME)).c_str());
        std::cout << "[System] Set default sink to " << SPEAKER_SINK_NAME << std::endl;
    }
}

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
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

    if (null_mode) std::cout << "!!! RUNNING IN NULL-AUDIO MODE (Bypassing Audio Hardware) !!!" << std::endl;
    if (test_tone_mode) std::cout << "!!! TEST TONE MODE ACTIVE: Generating 440Hz Sine Wave !!!" << std::endl;

    std::cout << "--- High-Performance Audio Pipe (Miniaudio Edition) ---\n";
    
    if (!null_mode) {
        setup_virtual_devices();
        
        if (current_mode == RunMode::MIC) {
            setenv("PULSE_SINK", MIC_SINK_NAME, 1);
            std::string src = std::string(MIC_SINK_NAME) + ".monitor";
            setenv("PULSE_SOURCE", src.c_str(), 1);
        } else if (current_mode == RunMode::SPEAKER) {
            setenv("PULSE_SINK", SPEAKER_SINK_NAME, 1);
            std::string src = std::string(SPEAKER_SINK_NAME) + ".monitor";
            setenv("PULSE_SOURCE", src.c_str(), 1);
        } else {
            setenv("PULSE_SINK", MIC_SINK_NAME, 1); 
            std::string src = std::string(SPEAKER_SINK_NAME) + ".monitor";
            setenv("PULSE_SOURCE", src.c_str(), 1);
        }
        std::cout << "[System] Mode: " << (current_mode == RunMode::MIC ? "MIC" : current_mode == RunMode::SPEAKER ? "SPEAKER" : "DUPLEX") << std::endl;
    }

    ma_device_config captureConfig = ma_device_config_init(ma_device_type_capture);
    captureConfig.capture.format = ma_format_s16;
    captureConfig.capture.channels = CHANNELS;
    captureConfig.sampleRate = current_sample_rate.load();
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
        std::cout << "[System] Capture disabled (Mode: " << (current_mode == RunMode::MIC ? "MIC" : "NULL") << ")." << std::endl;
    }

    ma_device_config playbackConfig = ma_device_config_init(ma_device_type_playback);
    playbackConfig.playback.format = ma_format_s16;
    playbackConfig.playback.channels = CHANNELS;
    playbackConfig.sampleRate = current_sample_rate.load();
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
        std::cout << "[System] Playback disabled (Mode: " << (current_mode == RunMode::SPEAKER ? "SPEAKER" : "NULL") << ")." << std::endl;
    }

    std::cout << "PC Capture (to Phone): " << (null_mode ? "NULL" : SPEAKER_SOURCE_NAME) << " -> " << PHONE_IP << ":" << SEND_PORT << std::endl;
    std::cout << "Phone (to PC): " << RECV_PORT << " -> PC Playback: " << (null_mode ? "NULL" : MIC_SINK_NAME) << std::endl;

    std::thread t_out(outbound_thread);
    std::thread t_in(inbound_thread);
    std::thread t_drain(jitter_drain_thread);
    std::thread t_mon(monitor_thread);

    std::cout << "Server running. Press Enter to stop." << std::endl;
    std::cin.get();

    running = false;
    t_out.join();
    t_in.join();
    t_drain.join();
    t_mon.join();

    if (capture_started) ma_device_uninit(&captureDevice);
    if (playback_started) ma_device_uninit(&playbackDevice);

    return 0;
}
