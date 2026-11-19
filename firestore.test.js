const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_user_123";
const BOB_UID = "bob_user_456";
const DATABASE_ID = "ai-studio-android-noomiai-06f1da1b-1fbe-46f2-989f-4c5147a70de8";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
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

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  const ts = pastDate();

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("sessions").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("assets").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("prompt_history").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_1").set({
      userId: ALICE_UID,
      title: "Unauthorized",
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Owner can create, update, list, and delete profile, session, asset, and prompt history", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const created = pastDate(5000);
  const updated = pastDate(1000);

  // 1. User profile
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Creator",
      email: "alice@example.com",
      createdAt: created,
      updatedAt: created,
    })
  );

  // 2. Session create + rename + list
  const sessionRef = aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_1");
  await assertSucceeds(
    sessionRef.set({
      userId: ALICE_UID,
      title: "Cyberpunk Shots",
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    sessionRef.update({
      title: "Cyberpunk Shots v2",
      updatedAt: updated,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").get()
  );

  // 3. Asset create + partial folderTag/trash update + list
  const assetRef = aliceDb.collection("users").doc(ALICE_UID).collection("assets").doc("asset_1");
  await assertSucceeds(
    assetRef.set({
      userId: ALICE_UID,
      sessionId: "sess_1",
      kind: "AI_IMAGE",
      prompt: "Futuristic skyline at dusk",
      modelLabel: "Gemini 4 Hydrogen",
      aspectRatio: "16:9",
      primaryFilePath: "/data/user/0/com.aistudio.noomiai.krmvqt/files/img_1.png",
      trashed: false,
      generationDurationMs: 2400,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    assetRef.update({
      folderTag: "Scenes",
      updatedAt: updated,
    })
  );
  await assertSucceeds(
    assetRef.update({
      trashed: true,
      trashedAtMs: Date.now(),
      updatedAt: pastDate(500),
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("assets").get()
  );

  // 4. Prompt history create + list + delete
  const histRef = aliceDb.collection("users").doc(ALICE_UID).collection("prompt_history").doc("hist_1");
  await assertSucceeds(
    histRef.set({
      userId: ALICE_UID,
      promptText: "Futuristic skyline at dusk",
      mediaMode: "IMAGE",
      aspectRatio: "16:9",
      modelLabel: "Gemini 4 Hydrogen",
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("prompt_history").get()
  );
  await assertSucceeds(histRef.delete());
  await assertSucceeds(assetRef.delete());
  await assertSucceeds(sessionRef.delete());
});

test("Cross-user access and shadow/invalid payloads are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore(DATABASE_ID);
  const created = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_alice").set({
      userId: ALICE_UID,
      title: "Alice Project",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Bob cannot read or write Alice's sessions/assets/history
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sessions").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_alice").update({
      title: "Hijacked",
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_alice").delete()
  );

  // Ghost field (`id` or `isAdmin`), orphaned sessionId, and immutable createdAt tampering rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_ghost").set({
      id: "sess_ghost",
      userId: ALICE_UID,
      title: "Ghost ID",
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("assets").doc("asset_orphan").set({
      userId: ALICE_UID,
      sessionId: "missing_session",
      kind: "AI_IMAGE",
      prompt: "Orphan asset",
      modelLabel: "Gemini 4 Hydrogen",
      aspectRatio: "16:9",
      primaryFilePath: "/tmp/img.png",
      trashed: false,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_alice").update({
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").doc("sess_future").set({
      userId: ALICE_UID,
      title: "Future timestamp",
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});
