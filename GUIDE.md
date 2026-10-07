# Using Déchaîner 2.0, bite by bite

Do one step a day if you like. Each step works on its own.

Home is a clock and one button. Tap the clock or swipe up for the menu.

---

## Step 1: Install and give it power (once)
1. Install the APK. **Never uninstall to update**: install new versions over the old one. Uninstalling removes the protection and your data.
2. Temporarily remove your Google and other accounts (*Settings → Passwords & accounts*). Android only allows device owner when no account is signed in.
3. Open the app, go to **Settings → Device Owner** and follow the steps (Shizuku), or run the `adb` command from the README on a computer.
4. Add your accounts back.
5. Allow notifications (and, on Android 14 and newer, full-screen alerts) and give **Usage access** once, for daily limits.

Check: **Settings** starts with a **Setup status** card. Every line should show a tick; a **Fix** button takes you to what is missing.

## Step 2: Set your recovery code (once)
The code is what lets you *loosen* a rule later. Give it to someone you trust and **never keep it on the phone or in a notebook next to it**. Anything that weakens protection asks for it, and you can add a waiting delay on top.

## Step 3: Add an AI key (optional)
**Settings → Urge and AI**. Pick a provider (Google AI Studio has a free tier; DeepSeek, OpenRouter, OpenAI and any OpenAI-compatible service also work), paste your key and agree to the privacy note. Without a key, urges get three fixed questions and no deep dive. The key is sealed with the Android Keystore.

## Step 4: Turn on adult-site blocking (2 minutes)
**Settings → Private DNS**, then pick **CleanBrowsing Family** (strictest). This covers the whole phone, every browser and app. Also open **Settings → Protections** and switch on the ones you want, for example blocking factory reset, installing from unknown sources, and adding a VPN.

## Step 5: When an urge hits
Tap **Urge** on Home (or the Quick Settings tile, or the icon shortcut), then **Ongoing**. The phone locks at once for ten minutes: calls, the dialer and the alarm clock work, everything else is paused (text messages and Emergency Info too). Nothing ends it early and a second tap never extends it. A breathing circle runs for the ten minutes. When it ends, write what happened if you want to ("Not now" leaves a counted entry), answer the questions, and read the deep dive.

If you already slipped, tap **I slipped** instead: no lock, straight to writing. A slip is one event; the deep dive names the gap that let it through.

Add the tile: pull the notification shade down fully, tap the pencil or "Edit", find the Urge tile, and drag it to the top row.

Set the **reason** you wrote on a calm day and one **person to call** in **Settings → Urge and AI**. The reason replaces the fifth breathing prompt; the person appears on a support card if a note shows risk. Neither is sent to the AI.

If the AI cannot be reached, the note stays on the phone, the entry waits for its deep dive, and the app retries in the background until it works or you delete the entry.

## Step 6: Focus sessions
1. **Manual:** open **Focus**, pick an end time (10 minutes to 8 hours), write what the session is for, choose usual or special, and commit.
2. **By schedule:** in **Schedules**, add an entry of type **Focus** (days, start, end). It starts by itself at its start time. A prompt on top of the lock asks "usual or special?" and what the session is for; if you ignore it for 2 minutes it runs as special.
3. **Usual:** Pomodoro phases inside the lock, with "Did you do the work?" after each focus phase. **No** starts a reset (5 minutes of meditation, 5 of something physical) and then asks if you are ready. If you are not, the rest of the block is a plain countdown. **Special:** one continuous lock and one question at the end.
4. The phone is locked until the end time, breaks included, and **nothing ends it early, not even your recovery code**. Start with a short one. Choose the apps allowed during a block in the timer settings; keep browsers off that list.

## Step 7: Your data
The deep dive (Step 5) is the one thing the AI writes. You can keep, back up or delete your data under **Settings → Your data**: delete a single urge, a focus session or a deep dive, save everything to a file and read it back, or wipe all data (it asks for the recovery code). A finished entry or session can be deleted at any time.

## Step 8: Make your first schedule (5 minutes)
1. Open **Schedules**.
2. Under **Quick start**, tap a preset: **Bedtime** (22:30 to 06:30), **Study hours** (09:00 to 17:00, weekdays), **Exam week** or **Night detox**.
3. The editor opens pre-filled. Change anything: name, days, times.
4. **Allow only** means everything is paused *except* the apps you list. Add the few you truly need. Leave it empty for "phone and essentials only".
5. Optionally turn on **Lock while active**: the schedule cannot be edited, disabled or deleted while its window is open, even with your recovery code. Start with it off until you trust your setup.

Tips: the clock is locked, so you can't skip a window by changing the time. A set of locked schedules that leaves under an hour free all week is refused.

## Step 9: Block single apps by hand, and set time limits
On **Apps and limits**, flip the switch next to any app to pause it. Open an app's row to set a daily limit; once it is used up, the app is suspended until midnight, even if Usage access is switched off. Lowering a limit is free; raising or removing it takes your recovery code.

## If you get locked out
- **Lost your recovery code:** *Settings → Forced removal*. Start it, and after **4 days** you can remove everything without the code. The timer can't be sped up.
- A lock bug is the other danger. Test new builds on a spare phone first and read BLUEPRINT.md section 14A before installing on your daily phone.

## A simple daily routine
1. Morning: start a focus session.
2. When an urge hits: tap Urge instead of arguing with yourself.
3. Now and then: save a backup file under **Settings → Your data**.

## Be realistic
This app removes access and adds friction. It doesn't fix boredom, stress or loneliness, and it can't touch your other devices. If phone or porn use is seriously hurting your life, pair the app with a friend, counsellor or doctor.
