const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write user profile or servers", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("servers").get());
});

test("Owner can create and update their PhotoServer and PairedDevice", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = nowTs();
  const serverRef = aliceDb.collection("users").doc(ALICE_UID).collection("servers").doc("srv_1");
  await assertSucceeds(
    serverRef.set({
      userId: ALICE_UID,
      serverName: "Pixel 4a Vault",
      hostAddress: "192.168.1.42",
      port: 8443,
      certFingerprint: "ABCDEF1234567890ABCDEF1234567890",
      pairingCodeHash: "hash_12345678",
      isOnline: true,
      storageLabel: "SanDisk 128GB SD",
      totalBytes: 128000000000,
      usedBytes: 32000000000,
      availableBytes: 96000000000,
      relayMode: "lan",
      lastHeartbeat: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    serverRef.update({
      isOnline: false,
      updatedAt: nowTs(),
    })
  );

  const deviceRef = aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev_1");
  await assertSucceeds(
    deviceRef.set({
      userId: ALICE_UID,
      serverId: "srv_1",
      deviceName: "Pixel 9",
      deviceModel: "Pixel 9 Pro",
      status: "active",
      lastSeenAt: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Cross-user access to servers, devices, and catalog is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = nowTs();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("servers").doc("srv_1").set({
      userId: ALICE_UID,
      serverName: "Alice Vault",
      hostAddress: "192.168.1.42",
      port: 8443,
      certFingerprint: "ABCDEF1234567890ABCDEF1234567890",
      pairingCodeHash: "hash_12345678",
      isOnline: true,
      storageLabel: "Internal",
      totalBytes: 64000000000,
      usedBytes: 1000000000,
      availableBytes: 63000000000,
      relayMode: "lan",
      lastHeartbeat: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("servers").doc("srv_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("servers").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("servers").doc("srv_1").update({
      isOnline: false,
      updatedAt: ts,
    })
  );
});

test("Shadow update with ghost field is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = nowTs();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(
    profileRef.set({
      userId: ALICE_UID,
      displayName: "Alice",
      preferredMode: "server",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    profileRef.update({
      preferredMode: "client",
      isAdmin: true,
      updatedAt: nowTs(),
    })
  );
});
