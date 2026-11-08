#pragma once
#include <cstdint>
#include <cstdio>

// uart_std_out_tx.hpp
//
// Decode a DUT UART TX pin (8N1, idle-high) and print received bytes to stdout.
//
// Call step(tx_line) exactly ONCE per *full* DUT clock cycle (ideally on posedge).
//
// Improvements vs. minimal decoder:
// - reset(current_tx_line) to synchronize edge detector to the current line level
// - start-bit validation: after detecting a falling edge, re-check in the middle
//   of the start bit that the line is still low before locking onto the frame.
//   This prevents a glitch/X during reset from corrupting the first character.
//
// Timing is derived from clk_hz and baud (no hardcoded magic numbers).
class UartStdOutTx {
public:
  UartStdOutTx(uint32_t clk_hz_, uint32_t baud_)
      : clk_hz(clk_hz_), baud(baud_) {
    // Rounded division for better accuracy than truncation
    ticks_per_bit = (clk_hz + baud / 2u) / baud;
    if (ticks_per_bit == 0) ticks_per_bit = 1;
    reset(true);
  }

  // Sync the decoder to the current TX pin level.
  // Call this while DUT is in reset, and once right after reset deassert.
  void reset(bool current_tx_line) {
    st = IDLE;
    last_line = current_tx_line;
    tick = 0;
    bit_idx = 0;
    cur = 0;
  }

  // Call once per full clock cycle (e.g., posedge only).
  void step(bool tx_line) {
    switch (st) {
      case IDLE:
        // Detect falling edge -> potential start bit
        if (last_line == true && tx_line == false) {
          st = CHECK_START_MID;
          // Wait half bit time to validate we are still in start bit
          tick = ticks_per_bit / 2u;
          if (tick == 0) tick = 1;
        }
        break;

      case CHECK_START_MID:
        if (countdown()) {
          if (tx_line == false) {
            // Valid start bit. Now wait 1 bit time to reach center of data bit 0
            st = WAIT_DATA0;
            tick = ticks_per_bit;
            cur = 0;
            bit_idx = 0;
          } else {
            // Glitch; go back idle
            st = IDLE;
          }
        }
        break;

      case WAIT_DATA0:
        if (countdown()) {
          sample_bit(tx_line);          // bit 0
          bit_idx = 1;
          st = WAIT_NEXT_BIT;
          tick = ticks_per_bit;
        }
        break;

      case WAIT_NEXT_BIT:
        if (countdown()) {
          if (bit_idx < 8) {
            sample_bit(tx_line);
            bit_idx++;
            tick = ticks_per_bit;
          } else {
            st = WAIT_STOP;
            tick = ticks_per_bit;
          }
        }
        break;

      case WAIT_STOP:
        if (countdown()) {
          if (tx_line == true) {
            emit_byte(cur);
          }
          st = IDLE;
        }
        break;

      default:
        st = IDLE;
        break;
    }

    last_line = tx_line;
  }

  void flush() { std::fflush(stdout); }
  uint32_t get_ticks_per_bit() const { return ticks_per_bit; }

private:
  enum State : uint8_t { IDLE, CHECK_START_MID, WAIT_DATA0, WAIT_NEXT_BIT, WAIT_STOP };

  uint32_t clk_hz = 0;
  uint32_t baud = 0;
  uint32_t ticks_per_bit = 1;

  State st = IDLE;
  bool last_line = true;
  uint32_t tick = 0;

  uint8_t cur = 0;
  uint8_t bit_idx = 0;

  bool countdown() {
    if (tick > 0) tick--;
    return tick == 0;
  }

  void sample_bit(bool line) {
    // UART sends LSB first
    if (line) cur |= (1u << bit_idx);
  }

  void emit_byte(uint8_t b) {
    std::putchar((int)b);
    std::fflush(stdout);
  }
};
