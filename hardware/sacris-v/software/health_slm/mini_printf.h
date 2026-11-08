#ifndef MINI_PRINTF_H
#define MINI_PRINTF_H

#include <stdarg.h>

extern void putc(char ch);

int mini_printf(const char *fmt, ...);

int mini_vprintf(const char *fmt, va_list ap);

#endif
