CXX = g++
CXXFLAGS = -O3 -Wall -pthread -I/root/android-audio-pipe/external/miniaudio
LDFLAGS = -lpthread -lm -ldl

TARGET = audio_pipe_server
SRC = server.cpp

all: $(TARGET)

$(TARGET): $(SRC)
	$(CXX) $(CXXFLAGS) $(SRC) -o $(TARGET) $(LDFLAGS)

clean:
	rm -f $(TARGET)
