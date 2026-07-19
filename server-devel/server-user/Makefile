CXX = g++
CXXFLAGS = -O3 -Wall -std=c++17 -pthread -I../../external/miniaudio
LDFLAGS = -lpthread -lm -ldl

TARGET = audio_pipe_user

SRC = server_user.cpp

all: $(TARGET)

$(TARGET): $(SRC) ../../external/miniaudio/miniaudio.h
	$(CXX) $(CXXFLAGS) $(SRC) -o $(TARGET) $(LDFLAGS)

clean:
	rm -f $(TARGET)

.PHONY: all clean
