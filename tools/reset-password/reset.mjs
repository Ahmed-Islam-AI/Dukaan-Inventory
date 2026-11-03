// Owner-only tool: set a new password for a Dukaan account (docs/ACCOUNTS.md § Phone + password).
// Phone accounts without an email can't use "Forgot password", so the shopkeeper asks you instead.
//
//   cd tools/reset-password && npm install
//   node reset.mjs <service-account-key.json> <phone or email> <new password>
//
// The key: Firebase console → Project settings → Service accounts → Generate new private key.
// Keep it secret and outside git (it is full admin access to your project).
import { readFileSync } from "node:fs";
import { initializeApp, cert } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const [keyPath, who, password] = process.argv.slice(2);
if (!keyPath || !who || !password) {
  console.error("usage: node reset.mjs <service-account-key.json> <phone or email> <new password>");
  process.exit(1);
}
if (password.length < 6) {
  console.error("password must be at least 6 characters");
  process.exit(1);
}
initializeApp({ credential: cert(JSON.parse(readFileSync(keyPath, "utf8"))) });

// Same normalisation as the app (PhoneNumbers.kt): 0300-1234567 → 923001234567.
function normalize(input) {
  let d = input.replace(/\D/g, "");
  if (d.startsWith("00")) d = d.slice(2);
  else if (d.startsWith("0")) d = "92" + d.slice(1);
  return d;
}

let uid;
if (who.includes("@")) {
  uid = (await getAuth().getUserByEmail(who)).uid;
} else {
  const doc = await getFirestore().collection("phoneLogins").doc(normalize(who)).get();
  if (!doc.exists) {
    console.error("no account for that phone number");
    process.exit(1);
  }
  uid = doc.get("uid");
}
await getAuth().updateUser(uid, { password });
console.log(`password changed for ${who} (uid ${uid}). Tell the shopkeeper the new password.`);
