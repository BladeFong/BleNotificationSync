using System;
using System.Text;
using BleNotificationWin.Crypto;

namespace BleNotificationWin;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }

    /// <summary>
    /// Test encryption/decryption functionality.
    /// </summary>
    public static void TestEncryption()
    {
        string packageName = "com.example.app";
        string message = "Hello, BLE Notification Sync!";

        Console.WriteLine($"Package: {packageName}");
        Console.WriteLine($"Message: {message}");

        // Encrypt
        byte[] plaintext = Encoding.UTF8.GetBytes(message);
        var encrypted = AesCcmCrypto.Encrypt(packageName, plaintext);

        Console.WriteLine($"Nonce: {Convert.ToHexString(encrypted.Nonce)}");
        Console.WriteLine($"Ciphertext: {Convert.ToHexString(encrypted.Ciphertext)}");

        // Decrypt
        byte[]? decrypted = AesCcmCrypto.Decrypt(packageName, encrypted.Nonce, encrypted.Ciphertext);

        if (decrypted != null)
        {
            string decryptedMessage = Encoding.UTF8.GetString(decrypted);
            Console.WriteLine($"Decrypted: {decryptedMessage}");
            Console.WriteLine($"Success: {message == decryptedMessage}");
        }
        else
        {
            Console.WriteLine("Decryption failed!");
        }
    }
}
