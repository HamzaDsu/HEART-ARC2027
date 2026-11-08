.section .rodata
.balign 16
.global swint_payload_start
swint_payload_start:
.incbin "/home/user/health-slm-model-validation/swint-fpga-embedded-20261102/swint_payload.bin"
.global swint_payload_end
swint_payload_end:
.section .note.GNU-stack,"",@progbits
