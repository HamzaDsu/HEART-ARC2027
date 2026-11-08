set part [lindex $argv 0]

if {[llength [get_parts -quiet $part]] == 0} {
    puts "STATUS=DEVICE_NOT_INSTALLED"
    exit 1
}

create_project -in_memory -part $part

set_property design_mode PinPlanning [current_fileset]
open_io_design -name io_1

puts "STATUS=DEVICE_FOUND"
puts "FPGA_PART=$part"

set total [llength [get_sites -quiet *]]
puts "TOTAL_SITES=$total"

foreach {label pattern} {
    SLICE_SITES SLICE*
    RAMB36_SITES RAMB36*
    RAMB18_SITES RAMB18*
    DSP_SITES DSP48*
} {
    set count [llength [get_sites -quiet $pattern]]
    puts "$label=$count"
}

if {$total == 0} {
    puts "ERROR=No physical sites available"
    exit 1
}

exit 0
