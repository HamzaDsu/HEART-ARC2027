package sacris_v.periphery

/*
 * Copyright: 2014-2018, Technical University of Denmark, DTU Compute
 * Author: Martin Schoeberl (martin@jopdesign.com)
 * License: Simplified BSD License
 *
 * A UART is a serial port, also called an RS232 interface.
 *
 */
// Simple UART (DTU) plus the 16550-compatible register front end Uart16550.
import chisel3._
import chisel3.util._

class UartIO extends DecoupledIO(UInt(8.W)) {
}

class Tx(frequency: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val txd = Output(UInt(1.W))
    val channel = Flipped(new UartIO())
  })

  val BIT_CNT = ((frequency + baudRate / 2) / baudRate - 1).asUInt

  val shiftReg = RegInit(0x7ff.U)
  val cntReg = RegInit(0.U(20.W))
  val bitsReg = RegInit(0.U(4.W))

  io.channel.ready := (cntReg === 0.U) && (bitsReg === 0.U)
  io.txd := shiftReg(0)

  when(cntReg === 0.U) {

    cntReg := BIT_CNT
    when(bitsReg =/= 0.U) {
      val shift = shiftReg >> 1
      shiftReg := 1.U ## shift(9, 0)
      bitsReg := bitsReg - 1.U
    } .otherwise {
      when(io.channel.valid) {

        shiftReg := 3.U ## io.channel.bits ## 0.U
        bitsReg := 11.U
      } .otherwise {
        shiftReg := 0x7ff.U
      }
    }

  } .otherwise {
    cntReg := cntReg - 1.U
  }
}

class Rx(frequency: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(UInt(1.W))
    val channel = new UartIO()
  })

  val BIT_CNT = ((frequency + baudRate / 2) / baudRate - 1)
  val START_CNT = ((3 * frequency / 2 + baudRate / 2) / baudRate - 2)

  val rxReg = RegNext(RegNext(io.rxd, 0.U), 0.U)
  val falling = !rxReg && (RegNext(rxReg) === 1.U)

  val shiftReg = RegInit(0.U(8.W))
  val cntReg = RegInit(BIT_CNT.U(20.W))
  val bitsReg = RegInit(0.U(4.W))
  val valReg = RegInit(false.B)

  when(cntReg =/= 0.U) {
    cntReg := cntReg - 1.U
  }.elsewhen(bitsReg =/= 0.U) {
    cntReg := BIT_CNT.U
    shiftReg := Cat(rxReg, shiftReg >> 1)
    bitsReg := bitsReg - 1.U

    when(bitsReg === 1.U) {
      valReg := true.B
    }
  }.elsewhen(falling) {
    cntReg := START_CNT.U
    bitsReg := 8.U
  }

  when(valReg && io.channel.ready) {
    valReg := false.B
  }

  io.channel.bits := shiftReg
  io.channel.valid := valReg
}

class Buffer extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new UartIO())
    val out = new UartIO()
  })

  object State extends ChiselEnum {
    val empty, full = Value
  }
  import State._

  val stateReg = RegInit(empty)
  val dataReg = RegInit(0.U(8.W))

  io.in.ready := stateReg === empty
  io.out.valid := stateReg === full

  when(stateReg === empty) {
    when(io.in.valid) {
      dataReg := io.in.bits
      stateReg := full
    }
  } .otherwise {
    when(io.out.ready) {
      stateReg := empty
    }
  }
  io.out.bits := dataReg
}

class BufferedTx(frequency: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val txd = Output(UInt(1.W))
    val channel = Flipped(new UartIO())
  })
  val tx = Module(new Tx(frequency, baudRate))
  val buf = Module(new Buffer())

  buf.io.in <> io.channel
  tx.io.channel <> buf.io.out
  io.txd <> tx.io.txd
}

class Uart16550(frequency: Int, baudRate: Int, txFifoDepth: Int = 64, rxFifoDepth: Int = 64,
                fwdStatusView: Boolean = false, fwdStatusPendW: Int = 3) extends Module {
  val io = IO(new Bundle {

    val txd = Output(Bool())
    val rxd = Input(Bool())

    val addr    = Input(UInt(3.W))
    val addrReg = Input(UInt(3.W))
    val wdata   = Input(UInt(8.W))
    val rdata   = Output(UInt(8.W))
    val wen     = Input(Bool())
    val ren     = Input(Bool())

    val irq = Output(Bool())

    val txPending = if (fwdStatusView) Some(Input(UInt(fwdStatusPendW.W))) else None
  })

  val tx = Module(new BufferedTx(frequency, baudRate))
  val rx = Module(new Rx(frequency, baudRate))

  io.txd := tx.io.txd.asBool
  rx.io.rxd := io.rxd.asUInt

  val txFifo = Module(new Queue(UInt(8.W), entries = txFifoDepth))

  txFifo.io.enq.valid := false.B
  txFifo.io.enq.bits  := 0.U

  tx.io.channel.valid := txFifo.io.deq.valid
  tx.io.channel.bits  := txFifo.io.deq.bits
  txFifo.io.deq.ready := tx.io.channel.ready

  val rxFifo = Module(new Queue(UInt(8.W), entries = rxFifoDepth))

  rxFifo.io.enq.valid := rx.io.channel.valid
  rxFifo.io.enq.bits  := rx.io.channel.bits
  rx.io.channel.ready := rxFifo.io.enq.ready

  rxFifo.io.deq.ready := false.B

  val rxDataLatch = RegInit(0.U(8.W))

  val dllReg = RegInit(1.U(8.W))
  val dlmReg = RegInit(0.U(8.W))
  val ierReg = RegInit(0.U(4.W))
  val fcrReg = RegInit(0.U(8.W))
  val lcrReg = RegInit(0x03.U(8.W))
  val mcrReg = RegInit(0.U(5.W))
  val scrReg = RegInit(0.U(8.W))

  val dlab = lcrReg(7)

  val lsr_dr   = rxFifo.io.deq.valid

  val lsr_thre =
    if (fwdStatusView) (txFifo.io.count +& io.txPending.get) < txFifoDepth.U
    else txFifo.io.enq.ready
  val lsr = Cat(0.U(1.W), lsr_thre, lsr_thre, 0.U(4.W), lsr_dr)

  val irq_rx = ierReg(0) && lsr_dr
  val irq_tx = ierReg(1) && lsr_thre
  io.irq := irq_rx || irq_tx

  val iir = Mux(irq_rx, "b11000100".U(8.W),
            Mux(irq_tx, "b11000010".U(8.W),
                        "b11000001".U(8.W)))

  val msr = "b10110000".U(8.W)

  when(io.wen) {
    switch(io.addr) {
      is(0.U) {
        when(dlab) {
          dllReg := io.wdata
        }.otherwise {
          txFifo.io.enq.valid := true.B
          txFifo.io.enq.bits  := io.wdata
        }
      }
      is(1.U) {
        when(dlab) {
          dlmReg := io.wdata
        }.otherwise {
          ierReg := io.wdata(3, 0)
        }
      }
      is(2.U) { fcrReg := io.wdata }
      is(3.U) { lcrReg := io.wdata }
      is(4.U) { mcrReg := io.wdata(4, 0) }

      is(7.U) { scrReg := io.wdata }
    }
  }

  when(io.ren && io.addr === 0.U && !dlab) {
    when(rxFifo.io.deq.valid) {
      rxDataLatch := rxFifo.io.deq.bits
      rxFifo.io.deq.ready := true.B
    }
  }

  val dlabReg_d = RegNext(dlab, false.B)

  io.rdata := 0.U
  switch(io.addrReg) {
    is(0.U) { io.rdata := Mux(dlabReg_d, dllReg, rxDataLatch) }
    is(1.U) { io.rdata := Mux(dlabReg_d, dlmReg, Cat(0.U(4.W), ierReg)) }
    is(2.U) { io.rdata := iir }
    is(3.U) { io.rdata := lcrReg }
    is(4.U) { io.rdata := Cat(0.U(3.W), mcrReg) }
    is(5.U) { io.rdata := lsr }
    is(6.U) { io.rdata := msr }
    is(7.U) { io.rdata := scrReg }
  }
}
