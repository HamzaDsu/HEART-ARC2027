
.section .rodata
.balign 16
.global vgg11_payload_start
vgg11_payload_start:
.incbin "/home/user/health-slm-model-validation/vgg11-fpga-embedded-20261102/vgg11_payload.bin"
.global vgg11_payload_end
vgg11_payload_end:
.section .note.GNU-stack,"",@progbits
