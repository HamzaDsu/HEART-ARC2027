
.section .rodata
.balign 16
.global resnet18_payload_start
resnet18_payload_start:
.incbin "/home/user/health-slm-model-validation/resnet18-fpga-embedded-20261102/resnet18_payload.bin"
.global resnet18_payload_end
resnet18_payload_end:
.section .note.GNU-stack,"",@progbits
