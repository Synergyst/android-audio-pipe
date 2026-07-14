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
#include <pulse/pulseaudio.h>
#include <pulse/simple.h>
#include <pthread.h>
#include <unistd.h>
#include <iomanip>
#include <chrono>
#include <map>

// Configuration
const char* PHONE_IP = "192.168.168.120"; 
const int RECV_PORT = 12345; 
const int SEND_PORT = 12346; 
const unsigned int SAMPLE_RATE = 44100;
const unsigned int CHANNELS = 1;
const size_t BUFFER_SIZE = 1024;

// Packet Types
enum PacketType : uint8_t {
    TYPE_AUDIO = 0x01,
    TYPE_PING = 0x02,
    TYPE_PONG = 0x03,
    TYPE_STATS = 0x04
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
};

std::atomic<bool> running(true);
ConnectionStats stats;
uint8_t CURRENT_SESSION[3] = {0xDE, 0xAD, 0xBE};

void set_rt_priority() {
    struct sched_param param;
    param.sched_priority = 99;
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) != 0) {
        std::cerr << "Warning: Failed to set SCHED_FIFO priority. Run as root!" << std::endl;
    }
}

void create_virtual_devices() {
    std::cout << "[System] Creating PulseAudio virtual devices..." << std::endl;
    system("pactl load-module module-null-sink sink_name=AndroidPipe sink_properties=device.description='AndroidAudioPipe_Speaker'");
    system("pactl load-module module-remap-source source_name=AndroidPipeMic source_properties=device.description='AndroidAudioPipe_Mic' master=AndroidPipe.monitor");
    std::cout << "[System] Virtual devices created." << std::endl;
}

void outbound_thread() {
    set_rt_priority();
    std::cout << "[Outbound] Thread started" << std::endl;

    pa_sample_spec ss;
    ss.format = PA_SAMPLE_S16LE;
    ss.channels = CHANNELS;
    ss.rate = SAMPLE_RATE;

    pa_simple *s = nullptr;
    int attempts = 0;
    while (running && !s && attempts < 10) {
        s = pa_simple_new(
            "/run/user/0/pulse/native", "AndroidPipe.monitor", PA_STREAM_RECORD, NULL, NULL, &ss, NULL, NULL, NULL
        );
        if (!s) {
            std::cerr << "[Outbound] Attempt " << ++attempts << "/10: Could not connect to PulseAudio source. Retrying in 1s..." << std::endl;
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }

    if (!s) {
        std::cerr << "[Outbound] Critical Error: Could not connect to PulseAudio source after 10 attempts." << std::endl;
        return;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in phone_addr;
    memset(&phone_addr, 0, sizeof(phone_addr));
    phone_addr.sin_family = AF_INET;
    phone_addr.sin_port = htons(SEND_PORT);
    inet_pton(AF_INET, PHONE_IP, &phone_addr.sin_addr);

    std::vector<uint8_t> audio_buffer(BUFFER_SIZE);
    uint32_t seq = 0;

    while (running) {
        if (pa_simple_read(s, audio_buffer.data(), BUFFER_SIZE, NULL) < 0) {
            std::cerr << "[Outbound] PulseAudio read error" << std::endl;
            break;
        }

        PacketHeader header;
        header.type = TYPE_AUDIO;
        memcpy(header.session_id, CURRENT_SESSION, 3);
        header.sequence = htonl(seq++);

        std::vector<uint8_t> packet(sizeof(PacketHeader) + BUFFER_SIZE);
        memcpy(packet.data(), &header, sizeof(PacketHeader));
        memcpy(packet.data() + sizeof(PacketHeader), audio_buffer.data(), BUFFER_SIZE);

        sendto(sock, packet.data(), packet.size(), 0, (struct sockaddr*)&phone_addr, sizeof(phone_addr));
    }

    pa_simple_free(s);
    close(sock);
}

void inbound_thread() {
    set_rt_priority();
    std::cout << "[Inbound] Thread started" << std::endl;

    pa_sample_spec ss;
    ss.format = PA_SAMPLE_S16LE;
    ss.channels = CHANNELS;
    ss.rate = SAMPLE_RATE;

    pa_simple *s = nullptr;
    int attempts = 0;
    while (running && !s && attempts < 10) {
        s = pa_simple_new(
            "/run/user/0/pulse/native", "AndroidPipe", PA_STREAM_PLAYBACK, NULL, NULL, &ss, NULL, NULL, NULL
        );
        if (!s) {
            std::cerr << "[Inbound] Attempt " << ++attempts << "/10: Could not connect to PulseAudio sink. Retrying in 1s..." << std::endl;
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }

    if (!s) {
        std::cerr << "[Inbound] Critical Error: Could not connect to PulseAudio sink after 10 attempts." << std::endl;
        return;
    }

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

    std::vector<uint8_t> buffer(BUFFER_SIZE + 128);
    while (running) {
        struct sockaddr_in from_addr;
        socklen_t from_len = sizeof(from_addr);
        ssize_t len = recvfrom(sock, buffer.data(), buffer.size(), 0, (struct sockaddr*)&from_addr, &from_len);
        
        if (len < (ssize_t)sizeof(PacketHeader)) continue;

        PacketHeader* header = (PacketHeader*)buffer.data();
        
        auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        stats.last_seen_ms = now_ms;
        stats.connected = true;

        if (header->type == TYPE_AUDIO) {
            uint32_t seq = ntohl(header->sequence);
            if (stats.last_sequence > 0 && seq > stats.last_sequence + 1) {
                stats.packets_lost += (seq - stats.last_sequence - 1);
            }
            stats.last_sequence = seq;
            stats.packets_received++;

            size_t payload_len = len - sizeof(PacketHeader);
            pa_simple_write(s, buffer.data() + sizeof(PacketHeader), payload_len, NULL);
        } else if (header->type == TYPE_PING) {
            PacketHeader pong;
            pong.type = TYPE_PONG;
            memcpy(pong.session_id, CURRENT_SESSION, 3);
            pong.sequence = 0;
            sendto(sock, &pong, sizeof(PacketHeader), 0, (struct sockaddr*)&from_addr, from_len);
        }
    }

    pa_simple_free(s);
    close(sock);
}

void monitor_thread() {
    while (running) {
        auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        
        long long diff = now_ms - stats.last_seen_ms;
        
        std::cout << "\r" << "[Status] " 
                  << (diff < 3000 ? "CONNECTED ✅" : "DISCONNECTED ❌") 
                  << " | Pkts: " << stats.packets_received 
                  << " | Loss: " << stats.packets_lost 
                  << " | Latency: " << diff << "ms " << std::flush;
        
        if (diff >= 3000) stats.connected = false;

        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    std::cout << std::endl;
}

int main() {
    std::cout << "--- High-Performance Audio Pipe (PulseAudio Edition) ---" << std::endl;
    
    create_virtual_devices();

    std::cout << "PC Capture: AndroidPipe.monitor -> Phone: " << PHONE_IP << ":" << SEND_PORT << std::endl;
    std::cout << "Phone: " << RECV_PORT << " -> PC Playback: AndroidPipe" << std::endl;

    std::thread t_out(outbound_thread);
    std::thread t_in(inbound_thread);
    std::thread t_mon(monitor_thread);

    std::cout << "Server running. Press Enter to stop." << std::endl;
    std::cin.get();

    running = false;
    t_out.join();
    t_in.join();
    t_mon.join();

    system("pactl unload-module module-null-sink");
    system("pactl unload-module module-remap-source");

    return 0;
}
