import sys
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
old_start=s.index("        private Map<String, String> auth() {")
old_end=s.index("        }\n    }\n}\n", old_start)
new='''        private Map<String, String> auth() {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Authorization", AzureSas.token(base(), sasKeyName, secrets.require(sasKeyRef), tokenExpiry.apply(Instant.now())));
            return h;
'''
s=s[:old_start]+new+s[old_end:]
for imp in ["import javax.crypto.Mac;\n","import javax.crypto.spec.SecretKeySpec;\n","import java.net.URLEncoder;\n","import java.util.Base64;\n"]:
    s=s.replace(imp,"",1)
s=s.replace("import org.sangam.integration.connector.file.FileTransport;\n","import org.sangam.integration.connector.file.FileTransport;\nimport org.sangam.integration.publish.cloud.AzureSas;\n",1)
open(p,'w',encoding='utf-8',newline='\n').write(s)
