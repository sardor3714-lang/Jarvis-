package uz.jarvis.assistant;

/** Oddiy matematik ifodalarni hisoblaydi: + - * / va qavslar. */
final class MathEval {
    private final String s;
    private int pos = -1;
    private int ch;

    private MathEval(String s) {
        this.s = s;
    }

    static double eval(String expression) {
        return new MathEval(expression).parse();
    }

    private void next() {
        ch = (++pos < s.length()) ? s.charAt(pos) : -1;
    }

    private boolean eat(int c) {
        while (ch == ' ') next();
        if (ch == c) {
            next();
            return true;
        }
        return false;
    }

    private double parse() {
        next();
        double x = expr();
        while (ch == ' ') next();
        if (pos < s.length()) throw new IllegalArgumentException("Kutilmagan belgi: " + (char) ch);
        return x;
    }

    private double expr() {
        double x = term();
        while (true) {
            if (eat('+')) x += term();
            else if (eat('-')) x -= term();
            else return x;
        }
    }

    private double term() {
        double x = factor();
        while (true) {
            if (eat('*')) x *= factor();
            else if (eat('/')) x /= factor();
            else return x;
        }
    }

    private double factor() {
        if (eat('+')) return factor();
        if (eat('-')) return -factor();
        double x;
        if (eat('(')) {
            x = expr();
            eat(')');
        } else if ((ch >= '0' && ch <= '9') || ch == '.') {
            int start = pos;
            while ((ch >= '0' && ch <= '9') || ch == '.') next();
            x = Double.parseDouble(s.substring(start, pos));
        } else {
            throw new IllegalArgumentException("Son kutilgan edi");
        }
        return x;
    }
}
