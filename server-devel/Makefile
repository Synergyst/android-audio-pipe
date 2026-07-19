CXX = g++
CXXFLAGS = -O3 -Wall -std=c++17 -pthread -I../external/miniaudio
LDFLAGS = -lpthread -lm -ldl

TARGET = audio_pipe_user
SRC = server_user.cpp
MINIAUDIO_SRC = miniaudio.c
MINIAUDIO_OBJ = miniaudio.o

DRAGONOS_USER = dragonos
DRAGONOS_HOST = 192.168.168.62
DRAGONOS_DEST = /home/$(DRAGONOS_USER)/

all: $(TARGET)

$(MINIAUDIO_OBJ): $(MINIAUDIO_SRC) ../external/miniaudio/miniaudio.h
	$(CXX) $(CXXFLAGS) -O2 -c $(MINIAUDIO_SRC) -o $(MINIAUDIO_OBJ)

$(TARGET): $(SRC) $(MINIAUDIO_OBJ)
	$(CXX) $(CXXFLAGS) $(SRC) $(MINIAUDIO_OBJ) -o $(TARGET) $(LDFLAGS)

clean:
	rm -f $(TARGET)

push: $(TARGET)
	scp $(TARGET) $(DRAGONOS_USER)@$(DRAGONOS_HOST):$(DRAGONOS_DEST)

.PHONY: all clean push
