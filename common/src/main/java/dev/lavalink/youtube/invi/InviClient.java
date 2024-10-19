package dev.lavalink.youtube.invi;

import java.util.List;

public class InviClient {
    private String apiRoute;
    private List<String> proxies;

    public InviClient(String apiRoute, List<String> proxies) {
        this.apiRoute = apiRoute;
        this.proxies = proxies;
    }

    public String getApiRoute() {
        return apiRoute;
    }

    public void setApiRoute(String apiRoute) {
        this.apiRoute = apiRoute;
    }

    public List<String> getProxies() {
        return proxies;
    }

    public void setProxies(List<String> proxies) {
        this.proxies = proxies;
    }
}
