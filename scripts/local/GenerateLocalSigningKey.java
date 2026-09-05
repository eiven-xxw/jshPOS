import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;

/** 生成仅用于本机开发的数据包 Ed25519 密钥；输出由 PowerShell 写入 Git 忽略文件。 */
public final class GenerateLocalSigningKey {
    private GenerateLocalSigningKey() {
    }

    public static void main(String[] args) throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encodedPublic = pair.getPublic().getEncoded();
        byte[] rawPublic = Arrays.copyOfRange(encodedPublic, encodedPublic.length - 32, encodedPublic.length);
        System.out.println("JSH_LOCAL_PACKAGE_SIGNING_PKCS8_B64="
            + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        System.out.println("JSH_LOCAL_PACKAGE_SIGNING_PUBLIC_B64="
            + Base64.getEncoder().encodeToString(rawPublic));
    }
}
