set rtl [lindex $argv 0]
set out [lindex $argv 1]

set_part xc7a200tsbg484-1
set_param general.maxThreads 2

puts "===== READ RTL ====="

read_verilog -sv $rtl

puts "===== SYNTHESIZE PROCESSOR ====="

synth_design \
    -top TopLevel \
    -part xc7a200tsbg484-1 \
    -flatten_hierarchy none

puts "===== APPLY CLOCK ====="

set clk [get_ports -quiet clock]

if {[llength $clk] != 1} {
    error "Expected exactly one TopLevel clock input"
}

create_clock \
    -name core_clk \
    -period 20.000 \
    $clk

puts "===== GENERATE REPORTS ====="

report_utilization \
    -file "$out/utilization.rpt"

report_utilization \
    -hierarchical \
    -file "$out/hierarchical_utilization.rpt"

report_timing_summary \
    -file "$out/timing_summary.rpt"

puts "===== SAVE CHECKPOINT ====="

write_checkpoint "$out/synth.dcp"

puts "PASS: SYNTHESIS REPORTS GENERATED"
