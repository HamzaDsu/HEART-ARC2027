#include <stdarg.h>
#include <stdint.h>

#define UART16550_BASE 0x69000000u
#define UART16550_THR  (*(volatile uint32_t*)(UART16550_BASE + 0x00u))
#define UART16550_LSR  (*(volatile uint32_t*)(UART16550_BASE + 0x14u))
#define LSR_THRE       (1u << 5)

void putc(char ch) {
    while ((UART16550_LSR & LSR_THRE) == 0u) {

        asm volatile ("nop");
    }
    UART16550_THR = (uint32_t)(uint8_t)ch;
}

static void print_char(char c)
{
    putc(c);
}

static void print_str(const char *s)
{
    if (!s) {
        print_str("(null)");
        return;
    }
    while (*s) {
        putc(*s++);
    }
}

static void print_uint(unsigned int value, unsigned int base, int upper)
{
    char buf[48];
    int i = 0;

    if (value == 0) {
        putc('0');
        return;
    }

    while (value != 0 && i < (int)sizeof(buf)) {
        unsigned int digit = value % base;
        value /= base;
        if (digit < 10)
            buf[i++] = '0' + digit;
        else
            buf[i++] = (upper ? 'A' : 'a') + (digit - 10);
    }

    while (i > 0) {
        putc(buf[--i]);
    }
}

static void print_int(int value)
{
    if (value < 0) {
        putc('-');

        print_uint((unsigned int)(-(value + 1)) + 1u, 10, 0);
    } else {
        print_uint((unsigned int)value, 10, 0);
    }
}

#ifdef MINI_PRINTF_FLOAT

static void print_float(double dv, int prec)
{
    if (prec < 0) prec = 6;
    if (prec > 9) prec = 9;

    union { float f; uint32_t u; } fb;
    fb.f = (float)dv;
    uint32_t bits = fb.u;
    uint32_t exp  = (bits >> 23) & 0xFFu;
    uint32_t mant = bits & 0x7FFFFFu;
    unsigned int sign = (bits >> 31) & 1u;

    if (exp == 0xFFu) {
        if (mant) { print_str("nan"); return; }
        if (sign) putc('-');
        print_str("inf");
        return;
    }

    float v = fb.f;
    if (sign) { putc('-'); v = -v; }

    static const uint32_t pow10[10] = {
        1u, 10u, 100u, 1000u, 10000u, 100000u,
        1000000u, 10000000u, 100000000u, 1000000000u
    };
    uint32_t scale = pow10[prec];

    uint32_t ip = (uint32_t)v;
    double frac = (double)v - (double)ip;
    uint32_t fpart = (uint32_t)(frac * (double)scale + 0.5);

    if (fpart >= scale) {
        ip += 1u;
        fpart -= scale;
    }

    print_uint(ip, 10, 0);

    if (prec > 0) {
        putc('.');
        char buf[9];
        int i;
        uint32_t fp = fpart;
        for (i = 0; i < prec; i++) {
            buf[prec - 1 - i] = (char)('0' + (fp % 10u));
            fp /= 10u;
        }
        for (i = 0; i < prec; i++) putc(buf[i]);
    }
}
#endif

int mini_vprintf(const char *fmt, va_list ap)
{
    int count = 0;
    char ch;

    while ((ch = *fmt++) != '\0') {
        if (ch != '%') {
            putc(ch);
            count++;
            continue;
        }

        ch = *fmt++;
        if (ch == '\0')
            break;

        int prec = -1;
        if (ch == '.') {
            prec = 0;
            while (*fmt >= '0' && *fmt <= '9') {
                prec = prec * 10 + (*fmt - '0');
                fmt++;
            }
            ch = *fmt++;
            if (ch == '\0')
                break;
        }

        switch (ch) {
        case '%':
            putc('%');
            count++;
            break;

        case 'c': {
            char c = (char)va_arg(ap, int);
            putc(c);
            count++;
            break;
        }

        case 's': {
            const char *s = va_arg(ap, const char *);
            if (!s)
                s = "(null)";
            while (*s) {
                putc(*s++);
                count++;
            }
            break;
        }

        case 'd':
        case 'i': {
            int v = va_arg(ap, int);

            print_int(v);

            break;
        }

        case 'u': {
            unsigned int v = va_arg(ap, unsigned int);
            print_uint(v, 10, 0);
            break;
        }

        case 'x': {
            unsigned int v = va_arg(ap, unsigned int);
            print_uint(v, 16, 0);
            break;
        }

        case 'X': {
            unsigned int v = va_arg(ap, unsigned int);
            print_uint(v, 16, 1);
            break;
        }

        case 'p': {
            uintptr_t v = (uintptr_t)va_arg(ap, void *);
            print_str("0x");
            print_uint((unsigned int)v, 16, 0);
            break;
        }

#ifdef MINI_PRINTF_FLOAT
        case 'f': {
            double v = va_arg(ap, double);
            print_float(v, prec);
            break;
        }
#endif

        default:

            putc('%');
            putc(ch);
            count += 2;
            break;
        }
    }

    return count;
}

int mini_printf(const char *fmt, ...)
{
    va_list ap;
    int ret;

    va_start(ap, fmt);
    ret = mini_vprintf(fmt, ap);
    va_end(ap);

    return ret;
}
