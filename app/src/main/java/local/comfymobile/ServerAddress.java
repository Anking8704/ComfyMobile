package local.comfymobile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class ServerAddress {
    private final String origin;
    private ServerAddress(String origin) { this.origin = origin; }

    public static ServerAddress parse(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("请输入电脑地址");
        String value = input.trim();
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || port == 0 || port > 65535) throw new IllegalArgumentException("请输入 HTTP(S) 根地址，例如 http://192.168.1.8:8188");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            return new ServerAddress(scheme + "://" + host + (defaultPort ? "" : ":" + port));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("电脑地址格式不正确", e);
        }
    }

    public String origin() { return origin; }

    public boolean owns(String url) {
        try {
            URI uri = new URI(url);
            return parse(new URI(uri.getScheme(), uri.getRawAuthority(), null, null, null).toString()).origin.equals(origin);
        } catch (IllegalArgumentException | URISyntaxException e) { return false; }
    }

    public URI resolve(String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\"))
            throw new IllegalArgumentException("仅允许当前 ComfyUI 的接口");
        URI uri = URI.create(origin + path);
        if (!owns(uri.toString())) throw new IllegalArgumentException("服务器来源不匹配");
        return uri;
    }
}
