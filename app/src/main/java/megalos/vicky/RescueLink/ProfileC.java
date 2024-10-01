package megalos.vicky.RescueLink;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ProfileC extends AppCompatActivity {

    private TextInputEditText editTextName;
    private TextInputEditText editTextPhoneNumber;
    private TextInputEditText editTextParentsPhoneNumber;
    private Button buttonCreateProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_c); // Ensure this matches your layout file

        editTextName = findViewById(R.id.editTextName);
        editTextPhoneNumber = findViewById(R.id.editTextPhoneNumber);
        editTextParentsPhoneNumber = findViewById(R.id.editTextParentsPhoneNumber);
        buttonCreateProfile = findViewById(R.id.buttonCreateProfile);

        buttonCreateProfile.setOnClickListener(view -> {
            if (validateInputs()) {
                createProfile();
                Intent main = new Intent(this,MainActivity.class);
                main.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(main);
            }
        });
    }

    private boolean validateInputs() {
        String name = editTextName.getText().toString().trim();
        String phoneNumber = editTextPhoneNumber.getText().toString().trim();
        String parentsPhoneNumber = editTextParentsPhoneNumber.getText().toString().trim();

        // Validate name (only alphabets and spaces)
        if (!name.matches("^[a-zA-Z\\s]*$")) {
            Toast.makeText(this, "Name can only contain alphabets and spaces.", Toast.LENGTH_SHORT).show();
            return false;
        }

        // Validate phone numbers (only digits)
        if (!phoneNumber.matches("^[0-9]*$") || phoneNumber.length() != 10) {
            Toast.makeText(this, "Phone number must be 10 digits long and contain only numbers.", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (!parentsPhoneNumber.matches("^[0-9]*$") || parentsPhoneNumber.length() != 10) {
            Toast.makeText(this, "Parents' phone number must be 10 digits long and contain only numbers.", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private void createProfile() {
        String name = editTextName.getText().toString().trim();
        String phoneNumber = editTextPhoneNumber.getText().toString().trim();
        String parentsPhoneNumber = editTextParentsPhoneNumber.getText().toString().trim();

        // Generate unique username
        String uniqueUsername = generateUsername(name, phoneNumber, parentsPhoneNumber);

        // Create JSON object
        JSONObject profileData = new JSONObject();
        try {
            profileData.put("name", name);
            profileData.put("phone_number", phoneNumber);
            profileData.put("parents_phone_number", parentsPhoneNumber);
            profileData.put("username", uniqueUsername);
        } catch (Exception e) {
            Log.e("MainActivity", "Error creating JSON object: " + e.getMessage());
        }

        // Save JSON to file
        saveProfileToFile("profile.json", profileData.toString());

        Toast.makeText(this, "Profile created successfully!", Toast.LENGTH_SHORT).show();
        finish(); // Or proceed to the next activity
    }

    private String generateUsername(String name, String phoneNumber, String parentsPhoneNumber) {
        String currentDateTime = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return name + "_" + phoneNumber.substring(0, 5) + "_" + currentDateTime + "_" + parentsPhoneNumber.substring(parentsPhoneNumber.length() - 5);
    }

    private void saveProfileToFile(String fileName, String jsonData) {
        try {
            FileOutputStream fileOutputStream = openFileOutput(fileName, Context.MODE_PRIVATE);
            fileOutputStream.write(jsonData.getBytes());
            fileOutputStream.close();
        } catch (Exception e) {
            Log.e("MainActivity", "Error saving profile: " + e.getMessage());
        }
    }
}
