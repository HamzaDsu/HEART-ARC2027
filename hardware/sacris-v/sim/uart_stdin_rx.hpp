#pragma once
#include <cstdint>
#include <deque>
#include <fstream>
#include <string>

// -----------------------------------------------------------------------------
// UartStdinRx: UART RX stimulus (8N1, idle-high)
// Sends two images back-to-back:
//   "IMEM" + len(u32 LE) + instruction_mem.bin
//   "DMEM" + len(u32 LE) + data_mem.bin
//
// Call step() exactly ONCE per FULL DUT clock cycle (posedge only).
// -----------------------------------------------------------------------------
class UartStdinRx {
public:
  UartStdinRx(uint32_t clkHz, uint32_t baudrate)
      : clk_hz(clkHz), baud(baudrate) {
    ticks_per_bit = (clk_hz + baud / 2u) / baud; // rounded
    if (ticks_per_bit == 0) ticks_per_bit = 1;

    rx_line = true;
    st = IDLE;
    tick = 0;
    bit_idx = 0;
  }

  void setStartDelayCycles(uint64_t cycles) { start_delay = cycles; }

  // Load both IMEM and DMEM files into the outgoing stream:
  // "IMEM"+len+imem_bytes then "DMEM"+len+dmem_bytes
  //
  // If requireImemWordAligned==true, IMEM length must be multiple of 4.
  bool loadProgramImages(const std::string& imemPath,
                         const std::string& dmemPath,
                         bool requireImemWordAligned = true) {
    q.clear();

    std::deque<uint8_t> imemPayload;
    if (!readFile(imemPath, imemPayload)) return false;

    if (requireImemWordAligned && ((imemPayload.size() & 3u) != 0u)) {
      return false;
    }

    std::deque<uint8_t> dmemPayload;
    if (!readFile(dmemPath, dmemPayload)) return false;

    appendHeaderLenPayload("IMEM", imemPayload);
    appendHeaderLenPayload("DMEM", dmemPayload);

    return true;
  }

  // True when all bytes have been sent and UART is idle
  bool done() const { return (start_delay == 0) && q.empty() && (st == IDLE); }

  // Call once per posedge; returns RX pin level
  bool step() {
    if (start_delay > 0) {
      start_delay--;
      rx_line = true;
      return rx_line;
    }
    advance_uart();
    return rx_line;
  }

private:
  enum State { IDLE, START, DATA, STOP };

  uint32_t clk_hz;
  uint32_t baud;
  uint32_t ticks_per_bit = 1;

  uint64_t start_delay = 0;
  std::deque<uint8_t> q;

  State st = IDLE;
  uint32_t tick = 0;
  uint8_t cur_byte = 0;
  uint8_t bit_idx  = 0;
  bool rx_line = true; // idle-high

  static bool readFile(const std::string& path, std::deque<uint8_t>& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.clear();
    char c;
    while (f.get(c)) out.push_back((uint8_t)(unsigned char)c);
    return true;
  }

  void appendU32LE(uint32_t v) {
    q.push_back((uint8_t)(v & 0xFFu));
    q.push_back((uint8_t)((v >> 8) & 0xFFu));
    q.push_back((uint8_t)((v >> 16) & 0xFFu));
    q.push_back((uint8_t)((v >> 24) & 0xFFu));
  }

  void appendHeaderLenPayload(const char header4[5], const std::deque<uint8_t>& payload) {
    // header4 must be 4 chars + '\0'
    q.push_back((uint8_t)header4[0]);
    q.push_back((uint8_t)header4[1]);
    q.push_back((uint8_t)header4[2]);
    q.push_back((uint8_t)header4[3]);

    appendU32LE((uint32_t)payload.size());

    for (uint8_t b : payload) q.push_back(b);
  }

  void start_frame() {
    cur_byte = q.front();
    q.pop_front();

    st = START;
    tick = ticks_per_bit;
    rx_line = false; // start bit
    bit_idx = 0;
  }

  void advance_uart() {
    if (st == IDLE) {
      rx_line = true;
      if (!q.empty()) start_frame();
      return;
    }

    if (tick > 0) tick--;

    if (tick == 0) {
      tick = ticks_per_bit;

      switch (st) {
        case START:
          st = DATA;
          rx_line = (cur_byte >> 0) & 1; // LSB first
          bit_idx = 1;
          break;

        case DATA:
          if (bit_idx < 8) {
            rx_line = (cur_byte >> bit_idx) & 1;
            bit_idx++;
          } else {
            st = STOP;
            rx_line = true; // stop bit
          }
          break;

        case STOP:
          st = IDLE;
          rx_line = true;
          break;

        default:
          st = IDLE;
          rx_line = true;
          break;
      }
    }
  }
};
