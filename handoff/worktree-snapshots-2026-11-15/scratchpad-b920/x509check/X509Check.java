import java.util.Base64;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x9.*;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
public class X509Check {
  public static void main(String[] a) throws Exception {
    byte[] ref = Base64.getDecoder().decode(a[0]);
    SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(ref);
    byte[] raw = spki.getPublicKeyData().getBytes();
    X9ECParameters c = CustomNamedCurves.getByName("curve25519");
    byte[] ours = new SubjectPublicKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, new X962Parameters(c)), raw).getEncoded(ASN1Encoding.DER);
    System.out.println("equal=" + java.util.Arrays.equals(ref, ours) + " refLen=" + ref.length + " oursLen=" + ours.length);
    System.out.println("ref =" + java.util.HexFormat.of().formatHex(ref));
    System.out.println("ours=" + java.util.HexFormat.of().formatHex(ours));
    System.out.println(org.bouncycastle.asn1.util.ASN1Dump.dumpAsString(ASN1Primitive.fromByteArray(ref)));
  }
}
