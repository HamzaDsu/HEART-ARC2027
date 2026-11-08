`timescale 1ns / 1ps

module nexys_video_top (
  input  logic       clock,
  input  logic       resetn,
  output logic [7:0] led,
  output logic       uart16550_tx,
  input  logic       uart16550_rx
);

  logic clk;
  logic locked;

  clk_wiz_0 u_clk_wiz (
    .clk_in1  (clock),
    .resetn   (resetn),
    .clk_out1 (clk),
    .locked   (locked)
  );

  logic        end_prog;
  logic [19:0] rst_cnt;
  logic        reset_s;

  always_ff @(posedge clk or negedge resetn) begin
    if (!resetn)            rst_cnt <= 20'hFFFFF;
    else if (!locked)       rst_cnt <= 20'hFFFFF;
    else if (end_prog)      rst_cnt <= 20'hFFFFF;
    else if (rst_cnt != 0)  rst_cnt <= rst_cnt - 1'b1;
  end

  assign reset_s = (rst_cnt != 0);

  logic [15:0] gpo;

  TopLevel u_sacris_v (
    .clock            (clk),
    .reset            (reset_s),

    .io_GPO           (gpo),
    .io_timerIRQ      (),
    .io_faultIRQ      (1'b0),
    .io_END           (end_prog),
    .io_reset_vector  (32'h0000_0000),

    .io_seg           (),
    .io_an            (),

    .io_spi_sclk      (),
    .io_spi_mosi      (),
    .io_spi_miso      (1'b0),
    .io_spi_cs        (),
    .io_spi_dc        (),
    .io_spi_rst       (),
    .io_spi_bl        (),

    .io_i2c_scl       (),
    .io_i2c_sda_o     (),
    .io_i2c_sda_oe    (),
    .io_i2c_sda_i     (1'b1),

    .io_uart16550_tx  (uart16550_tx),
    .io_uart16550_rx  (uart16550_rx)
  );

  assign led = gpo[7:0];

endmodule
