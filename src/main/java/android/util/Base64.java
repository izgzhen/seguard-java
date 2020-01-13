package android.util;

public class Base64 {
    public static byte[] decode(String s, int i) {
        assert i == 0;
        return java.util.Base64.getDecoder().decode(s);
    }
}
