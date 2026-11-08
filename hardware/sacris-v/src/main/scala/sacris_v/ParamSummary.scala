// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026, Humboldt-Universität zu Berlin
// Human-readable rendering of a TopParams instance and of the delta between two.

package sacris_v

final case class ParamDelta(field: String, group: ParamGroup, from: String, to: String)

sealed abstract class ParamGroup(val order: Int, val title: String, val caveat: Option[String])

object ParamGroup {
  case object System extends ParamGroup(0, "System / clocking", None)
  case object Isa    extends ParamGroup(1, "ISA / microarchitecture", None)
  case object Memory extends ParamGroup(2, "Memory / boot", None)
  case object Periph extends ParamGroup(3, "Peripherals", None)
  case object Dma    extends ParamGroup(4, "DMA subsystem", None)

  case object Safety extends ParamGroup(5, "Functional safety", Some(
    "PROVISIONAL — implemented and exercised in simulation only. No " +
    "qualification argument, no certification credit, and no claim that any " +
    "safety property is complete."))

  case object Instrumentation extends ParamGroup(6, "Measurement / diagnostic instrumentation", Some(
    "Not an operational feature — fault-injection and observability planes " +
    "that exist to measure the design."))

  case object Other extends ParamGroup(7, "Other", None)

  val ordered: Seq[ParamGroup] =
    Seq(System, Isa, Memory, Periph, Dma, Safety, Instrumentation, Other)

  private val instrumentation: Set[String] = Set(
    "enableIssueObservability",
    "withBranchObserve",
    "testDmaArbContention",
    "safetyVoterFi",
    "safetyEgressFi",
    "safetyMmioDropCounter",
    "regfileScrubSelfCheck",
    "regfileScrubSelfCheckSweeps",
    "regfileScrubSelfCheckDefeat",
  )

  private val isa: Set[String] = Set(
    "enableMExtension", "enableBExtension", "enableFExtension", "enableCExtension",
    "enableDualIssue", "enableIntraPairForward", "withLateLoadForward",
    "fpDivStepsPerCycle", "divSteps", "regOut",
    "dynPredBranch", "bhtEntries",
  )

  private val memory: Set[String] = Set(
    "imemSize", "dmemSize", "bootRomHex", "bootRomRegisteredData", "hostMemPort",
  )

  private val system: Set[String] = Set(
    "clkHz", "timerTickCompare", "mtimecmpReset",
  )

  private val periph: Set[String] = Set(
    "withSPI", "withI2C", "withSevenSegment", "withWDT",
    "withSpiLoopback", "withI2cLoopback",
  )

  def of(field: String): ParamGroup =
    if (instrumentation.contains(field)) Instrumentation
    else if (field.startsWith("safety") || field.startsWith("regfileScrub") || field == "withMemEcc" || field == "lockstepDelay") Safety
    else if (field.startsWith("dma") || field.startsWith("withDma")) Dma
    else if (isa.contains(field)) Isa
    else if (memory.contains(field)) Memory
    else if (system.contains(field)) System
    else if (periph.contains(field) || field.startsWith("uart") || field.startsWith("spi") || field.startsWith("i2c")) Periph
    else Other
}

trait ParamSummary { self: TopParams =>

  private def render(v: Any): String = v match {
    case s: Set[_] if s.isEmpty => "{}"
    case s: Set[_]              => s.map(_.toString).toSeq.sorted.mkString("{", ", ", "}")
    case b: BigInt              => s"0x${b.toString(16).toUpperCase}"
    case other                  => other.toString
  }

  def deltas(base: TopParams = TopParams()): Seq[ParamDelta] = {
    val names = self.productElementNames.toSeq
    val mine  = self.productIterator.toSeq
    val theirs = base.productIterator.toSeq
    names.zip(mine).zip(theirs).collect {
      case ((name, m), t) if m != t =>
        ParamDelta(name, ParamGroup.of(name), render(t), render(m))
    }
  }

  def summaryLine(name: String = "config", base: TopParams = TopParams(), baseName: String = "defaults"): String = {
    val d = deltas(base)
    if (d.isEmpty) s"$name = $baseName (no differences)"
    else s"$name = $baseName + ${d.map(_.field).mkString(", ")}"
  }

  def describe(name: String = "config", base: TopParams = TopParams(), baseName: String = "defaults"): String = {
    val d = deltas(base)
    val head =
      if (d.isEmpty) s"$name  =  $baseName (identical in all ${self.productArity} fields)"
      else s"$name  =  $baseName + ${d.size} change${if (d.size == 1) "" else "s"} (of ${self.productArity} fields)"

    val body = ParamGroup.ordered.flatMap { g =>
      val rows = d.filter(_.group == g)
      if (rows.isEmpty) Seq.empty
      else {
        val width = rows.map(_.field.length).max
        val caveat = g.caveat.toSeq.flatMap(c => wrap(c, 68).map(l => s"        $l"))
        Seq(s"  ${g.title}") ++ caveat ++
          rows.map(r => s"    ${r.field.padTo(width, ' ')}  ${r.from} -> ${r.to}")
      }
    }
    (head +: body).mkString("\n")
  }

  private def wrap(s: String, width: Int): Seq[String] =
    s.split(" ").foldLeft(Seq.empty[String]) { (lines, word) =>
      lines.lastOption match {
        case Some(last) if last.length + 1 + word.length <= width =>
          lines.init :+ s"$last $word"
        case _ => lines :+ word
      }
    }
}

object ConfigReport {
  def main(args: Array[String]): Unit = args.toSeq match {
    case Seq() =>
      TopParams.all.toSeq.sortBy(_._1).foreach { case (n, p) => println(p.summaryLine(n)) }

    case Seq(name) =>
      println(lookup(name).describe(name))

    case Seq(name, baseName) =>
      println(lookup(name).describe(name, lookup(baseName), baseName))

    case _ =>
      Console.err.println("usage: ConfigReport [<config> [<base-config>]]")
      sys.exit(2)
  }

  private def lookup(name: String): TopParams =
    TopParams.all.getOrElse(name, throw new IllegalArgumentException(
      s"unknown config '$name'. Known: ${TopParams.all.keys.toSeq.sorted.mkString(", ")}"))
}
