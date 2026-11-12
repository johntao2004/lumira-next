"""Install a synthetic third payment channel through the real plugin boundary in guarded QA.

No external payment network is called. All orders, credentials, and callbacks are fixtures.
"""
import hashlib
import importlib.util
import json
import os
import subprocess
import uuid
from pathlib import Path

spec = importlib.util.spec_from_file_location("plugin_smoke", Path(__file__).with_name("plugins-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
plugin = "qa-payment"


def package(owner, claimed=None):
    claimed = claimed or owner
    work = smoke.WORK / owner / uuid.uuid4().hex
    classes = work / "classes"
    classes.mkdir(parents=True)
    source = r'''package com.lumira.plugins.paymentprobe;
import com.lumira.plugin.sdk.PluginEntry;
import com.lumira.plugin.sdk.common.*;
import com.lumira.platform.common.*;
import com.lumira.platform.modules.ModuleEndpoint;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
public class ProbePlugin extends PluginEntry {
 public ProbePlugin(PluginWrapper wrapper){super(wrapper);}
 public Class<?> configuration(){return Config.class;}
 @Configuration(proxyBeanMethods=false) public static class Config {
  @Bean Gateway gateway(){return new Gateway();}
  @Bean Admin admin(PluginExtensionsPort extensions){return new Admin(extensions);}
  @Bean Callback callback(PluginExtensionsPort extensions){return new Callback(extensions);}
 }
 @RestController @ModuleEndpoint("__OWNER__") @RequestMapping("/api/plugins/__OWNER__")
 public static class Admin extends PaymentChannelAdminController {
  public Admin(PluginExtensionsPort extensions){super(extensions,new PaymentCoreAdminPort.ProviderPage("__OWNER__",PaymentCoreAdminPort.PageKind.ORDERS),new PaymentCoreAdminPort.ProviderPage("__OWNER__",PaymentCoreAdminPort.PageKind.SETTINGS));}
 }
 @RestController @ModuleEndpoint("__OWNER__") @RequestMapping("/api/plugins/__OWNER__")
 public static class Callback {
  private final PluginExtensionsPort extensions;
  public Callback(PluginExtensionsPort extensions){this.extensions=extensions;}
  @PostMapping("/callbacks/notify") @ContentPolicyExempt
  public ResponseEntity<Void> callback(HttpServletRequest request,@RequestBody @BoundedBody(maxBytes=4096,errorCode="paymentNotificationInvalid") String body){
   var headers=Map.of("x-fixture-signature",Objects.toString(request.getHeader("X-Fixture-Signature"),""));
   PaymentCoreAdminClient.call(extensions,core->{core.acceptCallback("__OWNER__",headers,body);return Boolean.TRUE;});
   return ResponseEntity.noContent().build();
  }
 }
 public static class Gateway implements PaymentGatewayPort {
  public String id(){return "__CLAIMED__";}
  public String callbackPath(){return "/api/plugins/__OWNER__/callbacks/notify";}
  public Set<String> secretFields(){return Set.of("credential");}
  public ValidationResult validate(Settings settings){return new ValidationResult(settings.secrets().containsKey("credential")?List.of():List.of("missingCredential"));}
  public PrecreateResult precreate(Settings settings,OrderRequest request){return new PrecreateResult("QA"+request.merchantOrderNo(),"https://qr.fixture.example/"+request.merchantOrderNo(),Instant.now().plusSeconds(300));}
  public TradeResult query(Settings settings,String order){return new TradeResult(TradeState.PENDING,"QA"+order,100,"CNY",null,null);}
  public void close(Settings settings,String order){}
  public RefundResult refund(Settings settings,RefundRequest request){return new RefundResult(RefundState.SUCCESS,"QA"+request.outRefundNo(),request.amountFen(),Instant.now(),null);}
  public RefundResult queryRefund(Settings settings,RefundRequest request){return refund(settings,request);}
  public Notification verifyCallback(Settings settings,Map<String,String> headers,String body){
   if(!"valid-fixture".equals(headers.get("x-fixture-signature")))throw new IllegalArgumentException("Invalid fixture signature");
   var json=new tools.jackson.databind.ObjectMapper().readTree(body);
   String order=json.path("order").asText();
   return new Notification(order,"QA"+order,json.path("amount").asLong(),"CNY",true,"fixture-event",Instant.now());
  }
 }
}'''.replace("__OWNER__", owner).replace("__CLAIMED__", claimed)
    java = work / "ProbePlugin.java"
    java.write_text(source)
    classpath = str(smoke.ROOT / "backend/target/classes") + os.pathsep + (smoke.ROOT / "backend/target/plugin-classpath.txt").read_text().strip()
    subprocess.run([str(smoke.JAVA / "javac"), "--release", "25", "-parameters", "-cp", classpath, "-d", str(classes), str(java)], check=True)
    jar_manifest = work / "MANIFEST.MF"
    jar_manifest.write_text(f"Manifest-Version: 1.0\nPlugin-Id: {owner}\nPlugin-Version: 1.0.0\nPlugin-Class: com.lumira.plugins.paymentprobe.ProbePlugin\n\n")
    jar = work / "plugin.jar"
    subprocess.run([str(smoke.JAVA / "jar"), "--create", "--date", "2020-01-01T00:00:00Z", "--file", str(jar), "--manifest", str(jar_manifest), "-C", str(classes), "."], check=True)
    manifest = json.loads((smoke.ROOT / "plugins/wechat-pay/plugin.json").read_text().replace("wechat-pay", owner))
    manifest.update(id=owner, name="Synthetic third payment channel", version="1.0.0", entryClass="com.lumira.plugins.paymentprobe.ProbePlugin")
    manifest['apiRules'].append(dict(method="POST", path=f"/api/plugins/{owner}/callbacks/notify", authorities=[], global_=False, publicAccess=True, signedCallback=True))
    manifest['apiRules'][-1]['global'] = manifest['apiRules'][-1].pop('global_')
    module = dict(id=owner, name=owner, version="1.0.0", dependencies=[d['id'] for d in manifest['dependencies']], routes=manifest['routes'])
    script = "(()=>{const m=" + json.dumps(module) + ";m.pages=Object.fromEntries(m.routes.map(r=>[r.id,()=> 'Synthetic QA payment page']));window.__lumiraRegisterPlugin(m.id,{module:m});})();"
    files = {'backend/plugin.jar': jar.read_bytes(), 'frontend/plugin.js': script.encode()}
    manifest['files'] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    files['plugin.json'] = json.dumps(manifest, sort_keys=True).encode()
    return smoke.zipbytes(files), manifest


core_before = next((row for row in smoke.jsoncall(smoke.P) if row['id'] == 'payment-core'), {'enabled': False})


def restore_enabled(owner, enabled):
    row = next(row for row in smoke.jsoncall('/platform/modules') if row['id'] == owner)
    if row['enabled'] != enabled:
        smoke.jsoncall('/platform/modules/' + owner + '/state', 'PUT', dict(enabled=enabled, version=row['stateVersion'], reason='Restore QA payment module state'))


def legacy_compatibility():
    owners = ['wechat-pay', 'alipay-pay', 'payment-core']
    before = {owner: {'installed': False, 'enabled': False} for owner in owners}
    before.update({row['id']: row for row in smoke.jsoncall(smoke.P) if row['id'] in owners})
    old_core = smoke.ROOT / 'plugins/payment-core/dist/payment-core-1.0.6.lumira-plugin.zip'
    old_channel = smoke.ROOT / 'plugins/wechat-pay/dist/wechat-pay-1.0.6.lumira-plugin.zip'
    old_alipay = smoke.ROOT / 'plugins/alipay-pay/dist/alipay-pay-1.0.6.lumira-plugin.zip'
    if not old_core.exists() or not old_channel.exists() or not old_alipay.exists():
        print('SKIP: published 1.0.6 packages unavailable for binary compatibility smoke', flush=True)
        for owner in reversed(owners):
            smoke.imports((smoke.ROOT / f'plugins/{owner}/dist/{owner}-1.1.0.lumira-plugin.zip').read_bytes())
            smoke.operate(owner, 'install')
        for owner in owners:
            restore_enabled(owner, before[owner]['enabled'])
        return
    for owner in owners:
        if before[owner]['installed']:
            smoke.operate(owner, 'uninstall')
    smoke.imports(old_core.read_bytes())
    smoke.imports(old_channel.read_bytes())
    smoke.imports(old_alipay.read_bytes())
    smoke.operate('wechat-pay', 'install')
    smoke.operate('alipay-pay', 'install')
    # The old provider JAR calls the published enum-based SDK constructor and methods.
    assert smoke.jsoncall('/plugins/wechat-pay/settings')['providerId'] == 'wechat-pay'
    assert isinstance(smoke.jsoncall('/plugins/wechat-pay/orders')['items'], list)
    smoke.sql("INSERT INTO plugin_payment_core_settings(provider_id,current_version,values_json,updated_by) VALUES('wechat-pay',1,'{}','fixture'); INSERT INTO plugin_payment_core_settings_history(provider_id,version,values_json,created_by) VALUES('wechat-pay',1,'{}','fixture'); INSERT INTO plugin_payment_core_order(order_id,idempotency_key,request_fingerprint,provider_id,settings_version,merchant_order_no,amount_fen,subject,status,created_by) VALUES(gen_random_uuid(),gen_random_uuid(),repeat('a',64),'wechat-pay',1,'QALEGACY',100,'Legacy fixture','CLOSED','fixture')")
    snapshot_sql = "SELECT row_to_json(t)::text FROM plugin_payment_core_settings t UNION ALL SELECT row_to_json(t)::text FROM plugin_payment_core_settings_history t UNION ALL SELECT row_to_json(t)::text FROM plugin_payment_core_order t ORDER BY 1"
    history_sql = "SELECT row_to_json(t)::text FROM plugin_payment_core_schema_history t WHERE version IN ('0','4') ORDER BY installed_rank"
    data_before = smoke.sql(snapshot_sql)
    history_before = smoke.sql(history_sql)
    smoke.operate('wechat-pay', 'uninstall')
    smoke.operate('alipay-pay', 'uninstall')
    smoke.operate('payment-core', 'uninstall')
    # Obsolete uninstalled channel catalog entries must not deadlock core upgrades.
    for owner in reversed(owners):
        smoke.imports((smoke.ROOT / f'plugins/{owner}/dist/{owner}-1.1.0.lumira-plugin.zip').read_bytes())
    for owner in reversed(owners):
        smoke.operate(owner, 'install')
    assert smoke.sql(snapshot_sql) == data_before
    assert smoke.sql(history_sql) == history_before
    assert smoke.sql("SELECT count(*) FROM plugin_payment_core_schema_history WHERE version='5' AND success") == '1'
    for owner in owners:
        restore_enabled(owner, before[owner]['enabled'])
    print('PASS: published 1.0.6 binary API remained usable; real V4-to-V5 plugin upgrade retained payment rows and original history', flush=True)


try:
    legacy_compatibility()
    data, manifest = package(plugin)
    smoke.imports(data)
    smoke.operate(plugin, 'install')
    role = smoke.sql("SELECT ur.role_id FROM platform_user_role ur JOIN platform_user u ON u.id=ur.user_id WHERE u.username='admin' ORDER BY ur.role_id LIMIT 1")
    uuid.UUID(role)
    for permission in manifest['permissions']:
        smoke.sql("INSERT INTO platform_role_authority(role_id,authority) VALUES('%s','%s') ON CONFLICT DO NOTHING" % (role, permission))
    base = '/plugins/' + plugin
    settings = smoke.jsoncall(base + '/settings', 'PUT', dict(expectedVersion=0, values=dict(notifyUrl='https://merchant.example/api/plugins/qa-payment/callbacks/notify'), secrets=dict(credential='synthetic-qa-only'), removeSecrets=[]))
    assert settings['configured'], settings
    request = dict(idempotencyKey=str(uuid.uuid4()), providerId=plugin, amountFen=100, subject='Synthetic QA order')
    order = smoke.jsoncall(base + '/orders', 'POST', request)
    assert order['providerId'] == plugin and order['status'] == 'PENDING', order
    repeated = smoke.jsoncall(base + '/orders', 'POST', request)
    assert repeated['orderId'] == order['orderId']
    wrong = dict(request, idempotencyKey=str(uuid.uuid4()), providerId='wechat-pay')
    assert smoke.write(smoke.admin, base + '/orders', 'POST', wrong)[0] == 404
    body = json.dumps(dict(order=order['merchantOrderNo'], amount=100)).encode()
    headers = {'Content-Type': 'application/json', 'X-Fixture-Signature': 'invalid-fixture'}
    assert smoke.call(smoke.client(), base + '/callbacks/notify', 'POST', body, headers)[0] == 400
    headers['X-Fixture-Signature'] = 'valid-fixture'
    for _ in range(2):
        assert smoke.call(smoke.client(), base + '/callbacks/notify', 'POST', body, headers)[0] == 204
    assert smoke.sql("SELECT status FROM plugin_payment_core_order WHERE order_id='%s'" % order['orderId']) == 'PAID'
    refunded = smoke.jsoncall(base + '/orders/' + order['orderId'] + '/refund', 'POST', dict(idempotencyKey=str(uuid.uuid4()), reason='Synthetic refund'))
    assert refunded['status'] == 'REFUNDED', refunded
    smoke.operate(plugin, 'uninstall')
    assert smoke.call(smoke.admin, base + '/orders')[0] != 200
    smoke.operate(plugin, 'install')
    assert smoke.jsoncall(base + '/orders')['items'][0]['orderId'] == order['orderId']
    assert smoke.jsoncall(base + '/settings')['version'] == settings['version']
    bad, _ = package('qa-payment-impostor', claimed=plugin)
    smoke.imports(bad)
    rejected = smoke.operate('qa-payment-impostor', 'install', 'FAILED')
    assert rejected['errorCode'] == 'pluginServiceOwnerConflict', rejected
    assert smoke.jsoncall(base + '/orders')['items'][0]['status'] == 'REFUNDED'
    print('PASS: third-channel settings, order idempotency, owned pages/callbacks, refund, data-preserving hotplug, and service impersonation rejection', flush=True)
finally:
    smoke.wait_for_readiness()
    for owner in ['qa-payment-impostor', plugin]:
        row = next((row for row in smoke.jsoncall(smoke.P) if row['id'] == owner), None)
        if row and row['installed']:
            smoke.operate(owner, 'uninstall')
    if not core_before['enabled']:
        restore_enabled('payment-core', False)
