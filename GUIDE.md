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

## Step 4: Confirm the checklist rules (once)
The last setup screen explains the daily checklist in plain words and asks for one confirmation. Enforcement starts when you confirm. See Step 8.

## Step 4b: App lock (optional, recommended)
**Settings → App lock**. Choose a PIN or a pattern that is **not** your phone's own. The app asks for it when it opens and after it has been out of sight for half a minute. Home (the clock and the Urge button), the Urge flow and a running lock never wait behind it, so an urge can always be started at once. Five wrong tries in a row make the app wait (30 seconds, then longer). Changing it asks for the current one; turning it off or "I forgot it" needs the recovery code, with its unlock delay. Best: have someone you trust choose it.

## Step 5: Turn on adult-site blocking (2 minutes)
**Settings → Private DNS**, then pick **CleanBrowsing Family** (strictest). This covers the whole phone, every browser and app. Also open **Settings → Protections** and switch on the ones you want, for example blocking factory reset, installing from unknown sources, and adding a VPN.

## Step 6: When an urge hits
Tap **Urge** on Home (or the Quick Settings tile, or the icon shortcut), then **Ongoing**. The phone locks at once for ten minutes: calls, the dialer and the alarm clock work, everything else is paused (text messages and Emergency Info too). Nothing ends it early and a second tap never extends it. A breathing circle runs for the ten minutes. When it ends, write what happened if you want to ("Not now" leaves a counted entry), answer the questions, and read the deep dive.

If you already slipped, tap **I slipped** instead: no lock, straight to writing. A slip is one event; the deep dive names the gap that let it through.

Add the tile: pull the notification shade down fully, tap the pencil or "Edit", find the Urge tile, and drag it to the top row.

Write your **reason** on a calm day in **Settings → Urge and AI**. It replaces the fifth breathing prompt and is shown again under every deep dive. It is never sent to the AI. If a note shows risk, a support card asks you to reach someone you trust and opens the phone app; the app stores no contact.

The questions after your note are built from what you wrote: the first link, what the urge was offering, how strong the pull was, the turning point, and a rule for the next time. The deep dive ends with a short plan (an if-then rule) and your own reason. It only uses facts from your note and answers; if you skip a question it says nothing about it. Do the first line of the plan before you close it.

Under **Settings → Urge and AI → Reply language** choose the language of the questions and the deep dive (English by default; empty follows the language of each note).

Every deep dive is kept: **menu → Deep dives** (also **Reports → Read your deep dives**, and the rows in **Your data**) opens it again with the answers behind it.

If the AI cannot be reached, the note stays on the phone, the entry waits for its deep dive, and the app retries in the background until it works or you delete the entry.

## Step 7: Focus sessions
1. **Manual:** open **Focus**, pick an end time (10 minutes to 8 hours), write what the session is for, choose usual or special, and commit.
2. **By schedule:** in **Schedules**, add an entry of type **Focus** (days, start, end). It starts by itself at its start time. A prompt on top of the lock asks "usual or special?" and what the session is for; if you ignore it for 2 minutes it runs as special.
3. **Usual:** Pomodoro phases inside the lock, with "Did you do the work?" after each focus phase. **No** starts a reset (5 minutes of meditation, 5 of something physical) and then asks if you are ready. If you are not, the rest of the block is a plain countdown. **Special:** one continuous lock and one question at the end.
4. The phone is locked until the end time, breaks included, and **nothing ends it early, not even your recovery code**. Start with a short one. Choose the apps allowed during a block in the timer settings; keep browsers off that list.

## Step 8: The daily checklist and the punishment day
Open **Today**. Each evening from 20:00 to 23:59 you write tomorrow's plan (3 to 7 goals) and close today's goals (done or not done). Manual goals can be ticked done at any time during the day.

The next day is a **punishment day** if tomorrow has no plan, if a goal of today is left unresolved, or if fewer than half of today's goals are done. On a punishment day the phone is locked from 00:00 to 23:59:59 (calls and the alarm clock stay open), settings are frozen, and only the Urge flow, Today and Reports work. A punishment day never follows a punishment day.

You may declare **one rest day** in any seven days from Today, in the evening window. It skips that day's goals and focus sessions, but it never cancels a punishment.

## Step 9: Weekly reports and your data
When a week with something recorded ends, the AI writes a report (week at a glance, urge and slip chains, focus, progress, where you are heading, follow-up, three actions for next week). It needs a key, your consent and a connection; if the phone was off, it is made the next time it is on and online. A notification opens it. **Reports** also shows a line of your daily checklist results.

**Reports → Your data:** delete a single entry, session, deep dive or report, back up everything to a file and read it back, or wipe all data (it asks for the recovery code). Punishment days, rest days and your weeks are never reset by deleting. A slip or session of today cannot be deleted until its day is over, and goal text can be deleted once a report covers its day.

## Step 10: Make your first schedule (5 minutes)
1. Open **Schedules**.
2. Under **Quick start**, tap a preset: **Bedtime** (22:30 to 06:30), **Study hours** (09:00 to 17:00, weekdays), **Exam week** or **Night detox**.
3. The editor opens pre-filled. Change anything: name, days, times.
4. **Allow only** means everything is paused *except* the apps you list. Add the few you truly need. Leave it empty for "phone and essentials only".
5. Optionally turn on **Lock while active**: the schedule cannot be edited, disabled or deleted while its window is open, even with your recovery code. Start with it off until you trust your setup.

Tips: the clock is locked, so you can't skip a window by changing the time. A set of locked schedules that leaves under an hour free all week is refused.

## Step 11: Block single apps by hand, and set time limits
On **Apps and limits**, flip the switch next to any app to pause it. Open an app's row to set a daily limit; once it is used up, the app is suspended until midnight, even if Usage access is switched off. Lowering a limit is free; raising or removing it takes your recovery code.

## If you get locked out
- **Lost your recovery code:** *Settings → Forced removal*. Start it, and after **4 days** you can remove everything without the code. The timer can't be sped up.
- A lock bug is the other danger. Test new builds on a spare phone first and read BLUEPRINT.md section 14A before installing on your daily phone.

## A simple daily routine
1. Morning: look at Today, then start a focus session.
2. When an urge hits: tap Urge instead of arguing with yourself.
3. Evening, 20:00: write tomorrow's goals and close today.
4. Sunday: read your weekly report, and keep a backup file.

## Be realistic
This app removes access and adds friction. It doesn't fix boredom, stress or loneliness, and it can't touch your other devices. If phone or porn use is seriously hurting your life, pair the app with a friend, counsellor or doctor.
