set rtl [lindex $argv 0]
set out [lindex $argv 1]

set_part xc7a200tsbg484-1
set_param general.maxThreads 2

read_verilog -sv $rtl

# Match the existing IMF_SLM_128K resource baseline.
synth_design \
    -top TopLevel \
    -part xc7a200tsbg484-1 \
    -flatten_hierarchy none

report_utilization \
    -file "$out/utilization.rpt"

report_utilization \
    -hierarchical \
    -file "$out/hierarchical_utilization.rpt"

write_checkpoint "$out/synth.dcp"

puts "PASS: RESOURCE SYNTHESIS COMPLETE"
