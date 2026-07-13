using System;
using System.Text;

namespace BleNotificationWin.Crypto;

/// <summary>
/// Simple test class for crypto functionality.
/// Run with: dotnet test (if test project is set up)
/// </summary>
public static class CryptoTests
{
    public static void RunTests()
    {
        Console.WriteLine("Running crypto tests...");

        TestKeyDerivation();
        TestEncryptDecrypt();
        TestDecryptWithWrongKey();

        Console.WriteLine("All tests passed!");
    }

    static void TestKeyDerivation()
    {
        Console.Write("Test key derivation... ");

        string packageName = "com.example.app";
        byte[] key1 = KeyDerivation.DeriveKey(packageName);
        byte[] key2 = KeyDerivation.DeriveKey(packageName);

        if (key1.Length != 32)
            throw new Exception("Key length should be 32 bytes");

        if (!key1.AsSpan().SequenceEqual(key2))
            throw new Exception("Same package name should derive same key");

        Console.WriteLine("OK");
    }

    static void TestEncryptDecrypt()
    {
        Console.Write("Test encrypt/decrypt... ");

        string packageName = "com.example.app";
        string message = "Hello, BLE Notification Sync!";
        byte[] plaintext = Encoding.UTF8.GetBytes(message);

        var encrypted = AesCcmCrypto.Encrypt(packageName, plaintext);

        if (encrypted.Nonce.Length != 12)
            throw new Exception("Nonce length should be 12 bytes");

        byte[]? decrypted = AesCcmCrypto.Decrypt(packageName, encrypted.Nonce, encrypted.Ciphertext);

        if (decrypted == null)
            throw new Exception("Decryption failed");

        string decryptedMessage = Encoding.UTF8.GetString(decrypted);
        if (message != decryptedMessage)
            throw new Exception("Decrypted message doesn't match original");

        Console.WriteLine("OK");
    }

    static void TestDecryptWithWrongKey()
    {
        Console.Write("Test decrypt with wrong key... ");

        string packageName1 = "com.example.app1";
        string packageName2 = "com.example.app2";
        string message = "Secret message";
        byte[] plaintext = Encoding.UTF8.GetBytes(message);

        var encrypted = AesCcmCrypto.Encrypt(packageName1, plaintext);

        // Try to decrypt with wrong package name (different key)
        byte[]? decrypted = AesCcmCrypto.Decrypt(packageName2, encrypted.Nonce, encrypted.Ciphertext);

        if (decrypted != null)
            throw new Exception("Decryption with wrong key should fail");

        Console.WriteLine("OK");
    }
}
