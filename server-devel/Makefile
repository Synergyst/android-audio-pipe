CXX = g++
CXXFLAGS = -O3 -Wall -pthread
LDFLAGS = -lpulse -lpulse-simple -lpthread

TARGET = audio_pipe_server
SRC = server.cpp

all: $(TARGET)

$(TARGET): $(SRC)
	$(CXX) $(CXXFLAGS) $(SRC) -o $(TARGET) $(LDFLAGS)

clean:
	rm -f $(TARGET)
