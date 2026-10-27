package local.comfymobile;

public class ServerAddressTest {
    static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    static void rejects(String input) {
        try { ServerAddress.parse(input); throw new AssertionError("Accepted " + input); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        ServerAddress server = ServerAddress.parse(" 192.0.2.10:8188/ ");
        eq("http://192.0.2.10:8188", server.origin());
        eq("https://home.example", ServerAddress.parse("https://HOME.example:443/").origin());
        eq("http://[::1]:8188", ServerAddress.parse("http://[::1]:8188").origin());
        eq("http://192.0.2.10:8188/history", server.resolve("/history").toString());
        for (String bad : new String[]{"", "file:///etc/passwd", "http://user:pass@home:8188", "http://home:0", "http://home:99999", "http://home/path", "http://home?x=1", "http://home#x", "http://home\\evil"}) rejects(bad);
        try { server.resolve("https://other.example/view"); throw new AssertionError("foreign origin"); } catch (IllegalArgumentException expected) { }
        try { server.resolve("//other.example/view"); throw new AssertionError("protocol relative"); } catch (IllegalArgumentException expected) { }
        eq(true, server.owns("http://192.0.2.10:8188/view?filename=a.png"));
        eq(false, server.owns("http://192.0.2.10:8188.evil.example/view"));
        System.out.println("ServerAddress: all cases passed");
    }
}
