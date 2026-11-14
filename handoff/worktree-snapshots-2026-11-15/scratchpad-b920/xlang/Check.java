import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.sangam.field.FieldJson;
import org.sangam.field.evidence.CapturePacket;
import org.sangam.field.evidence.PacketHasher;

/** One-off: the server's own parsing + PacketHasher over packets produced by the PWA code. */
public class Check {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        ObjectMapper spring = new ObjectMapper(); // request body → Map, default number handling (Double for decimals)
        Map<String, Object> in = spring.readValue(Files.readString(Path.of(a[0])), Map.class);
        String spki = (String) in.get("spki");
        for (Map<String, Object> o : (List<Map<String, Object>>) in.get("packets")) {
            Map<String, Object> body = spring.readValue((String) o.get("json"), Map.class);
            CapturePacket p = FieldJson.convert(body, CapturePacket.class); // FieldRestService.upload
            String canon = PacketHasher.canonical(p);
            String hash = PacketHasher.hash(p);
            System.out.println("canonical equal: " + canon.equals(o.get("canonical")));
            if (!canon.equals(o.get("canonical"))) {
                System.out.println(" java: " + canon);
                System.out.println(" pwa : " + o.get("canonical"));
            }
            System.out.println("hash equal: " + hash.equals(o.get("hash")) + " declared=" + hash.equals(p.packetHash()) + " " + hash);
            System.out.println("signature valid: " + PacketHasher.verifySignature(spki, "EC", p.packetHash(), p.signature()));
        }
        System.out.println(org.sangam.core.layer.Hashes.uuidV5(java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "python.org"));
    }
}
