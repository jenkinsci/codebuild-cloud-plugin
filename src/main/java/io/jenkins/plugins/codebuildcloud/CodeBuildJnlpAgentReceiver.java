package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jenkinsci.remoting.engine.JnlpConnectionState;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

import hudson.Extension;
import hudson.model.Computer;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import inet.ipaddr.IPAddressString;
import jenkins.model.Jenkins;
import jenkins.slaves.DefaultJnlpSlaveReceiver;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

//import org.json.simple.JSONObject;

@Extension(ordinal = 10)
public class CodeBuildJnlpAgentReceiver extends DefaultJnlpSlaveReceiver {
  private static final Logger LOGGER = Logger.getLogger(CodeBuildComputer.class.getName());

  private static transient long lastcacheTime = 0;

  private static transient List<IPAddressString> allowedIPs = new ArrayList<IPAddressString>();

  private static String getAmazonIPInfo() {
    LOGGER.info("getAmazonIPInfo BEGIN");
    HttpClient client = HttpClient.newBuilder()
        .version(Version.HTTP_1_1)
        .followRedirects(Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://ip-ranges.amazonaws.com/ip-ranges.json"))
        .build();

    try {
      HttpResponse<String> resp = client.send(request, BodyHandlers.ofString());
      assert resp.statusCode() == 200;
      LOGGER.info("getAmazonIPInfo END");
      return resp.body();
    } catch (IOException | InterruptedException e) {
      // Swallow
      LOGGER.info("getAmazonIPInfo END FAIL");
      return null;
    }
  }

  private static void parseAmazonResponse(Map<String, List<String>> map, String key, JSONArray jsonArray) {
    for (Object ob : jsonArray) {

      JSONObject inner = JSONObject.fromObject(ob);

      String cidr = inner.get(key).toString();
      String service = inner.get("service").toString();

      if (service.equals("AMAZON") || service.equals("CODEBUILD")) {
        map.get("CODEBUILD").add(cidr);
      } else if (service.equals("EC2")) {
        map.get("EC2").add(cidr);
      }
    }
  }

  private synchronized static void refreshCache() {
    long timeDiff = System.currentTimeMillis() - lastcacheTime;

    // Refresh every 24 hours?
    if (!(timeDiff > 1000 * 60 * 60 * 24)) {
      return;
    }

    lastcacheTime = System.currentTimeMillis();

    // Defaults to not allow any IP addresses if we dont properly get amazon information.
    allowedIPs = new ArrayList<IPAddressString>(); 
    String body = getAmazonIPInfo();
    if (body == null) {
      return;
    }

    Map<String, List<String>> map = new HashMap<String, List<String>>();
    map.put("EC2", new ArrayList<String>());
    map.put("CODEBUILD", new ArrayList<String>());

    JSONObject json = JSONObject.fromObject(body);

    parseAmazonResponse(map, "ip_prefix", json.getJSONArray("prefixes"));
    parseAmazonResponse(map, "ipv6_prefix", json.getJSONArray("ipv6_prefixes"));

    // Lists are now full - remove EC2 from CODEBUILD based on
    // https://docs.aws.amazon.com/general/latest/gr/aws-ip-ranges.html#aws-ip-egress-control

    List<IPAddressString> final_list = new ArrayList<IPAddressString>();
    for (String ip : map.get("CODEBUILD")) {
      if (!map.get("EC2").contains(ip)) {
        final_list.add(new IPAddressString(ip));
      }
    }

    LOGGER.info("Allowed AWS CodeBuild IPs Length refresh: " + final_list.size());
    allowedIPs = final_list;
  }

  @Override
  public boolean owns(String clientName) {
    Computer computer = Jenkins.get().getComputer(clientName);
    return computer != null && computer instanceof CodeBuildComputer;
  }

  @Override
  public void afterProperties(JnlpConnectionState event) {

    String clientName = event.getProperty(JnlpConnectionState.CLIENT_NAME_KEY);
    SlaveComputer computer = (SlaveComputer) Jenkins.get().getComputer(clientName);

    // Use default behavior if no computer
    if (computer == null) {
      super.afterProperties(event);
      return;
    }

    ComputerLauncher launcher = computer.getLauncher();

    // use default behavior if not our launcher
    if (!(launcher instanceof CodeBuildLauncher)) {
      super.afterProperties(event);
      return;
    }

    // Then it is within our domain to accept/reject
    CodeBuildLauncher ourLauncher = (CodeBuildLauncher) launcher;

    // Is enabled? Use default if not
    if (!ourLauncher.cloud.getVerifyIsCodeBuildIPOnJNLP()) {
      super.afterProperties(event);
      return;
    }

    // Enabled - Check to make sure originating IP is from CodeBuild
    // Before we check, do we need to refresh the cache?
    refreshCache();

    // Check now
    IPAddressString agentRequestIP = new IPAddressString(event.getSocket().getInetAddress().getHostAddress());

    boolean valid_ip = false;
    for (IPAddressString amazoncidr : allowedIPs) {
      if (amazoncidr.contains(agentRequestIP)) {
        valid_ip = true;
        break;
      }
    }

    LOGGER.fine("Is Valid IP: " + valid_ip);
    if (valid_ip) {
      // Is a CodeBuild IP - allow the rest of the logic to run.
      super.afterProperties(event);
    } else {
      // Is not a CodeBuild IP - Break off any connectivity.
      event.reject(new ConnectionRefusalException("Invalid Source IP, was not from AWS CodeBuild"));
    }
  }

  @Override
  public void channelClosed(JnlpConnectionState event) {
    // Build stopped from CodeBuild side, dont call the parent
    // super.channelClosed(event);
  }
}