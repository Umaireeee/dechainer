# Using Déchaîner, bite by bite

Do one step a day if you like. Each step works on its own.

The app has four tabs along the bottom: **Focus**, **Apps**, **Schedules** and **Settings**.

---

## Step 1: Install and give it power (once)
1. Install the APK. **Never uninstall to update**: install new versions over the old one. Uninstalling removes the protection and your settings.
2. Temporarily remove your Google and other accounts (*Settings → Passwords & accounts*). Android only allows device owner when no account is signed in.
3. Open the app, go to **Settings → Owner privileges** and follow the steps (Shizuku), or run the `adb` command from the README on a computer.
4. Add your accounts back.
5. Allow notifications when asked. The Pomodoro alarm rings through them.

Check: **Settings → Owner privileges** shows a green **Granted** badge.

## Step 2: Set your recovery code (once)
The code is what lets you *loosen* a rule later. Write it down on paper and keep it away from your phone, or give it to someone you trust. Anything that weakens protection asks for it, and you can add a waiting delay on top.

## Step 3: Turn on adult-site blocking (2 minutes)
**Settings → Private DNS**, then pick **CleanBrowsing Family** (strictest). This covers the whole phone, every browser and app.

Also open **Settings → Protections** and switch on the ones you want, for example blocking factory reset, installing from unknown sources, and adding a VPN.

## Step 4: Make your first schedule (5 minutes)
1. Open the **Schedules** tab.
2. Under **Quick start**, tap a preset:
   - **Bedtime**: 22:30 to 06:30, every day.
   - **Study hours**: 09:00 to 17:00, weekdays.
   - **Exam week**: 08:00 to 22:00, every day. Also blocks installing apps and adding a VPN.
   - **Night detox**: 23:00 to 05:00, every day. Same extra blocks.
3. The editor opens pre-filled. Change anything: name, days, times.
4. **Allow only** is on. That means everything is paused *except* the apps you list. Add the few you truly need (lecture app, notes, PDF reader, one AI chat app). Leave the list empty for "phone and essentials only".
5. Optionally turn on **Lock while active**. Then the schedule can't be edited, disabled or deleted while its window is open, even with your recovery code. Start with it off until you trust your setup.
6. Tap save. The schedule switches on and off by itself from then on.

Tips: the clock is locked while schedules are on, so you can't skip a window by changing the time. Long locked windows ask you to confirm, and a set of locked schedules that leaves under an hour free all week is refused, so you can't lock yourself out completely.

## Step 5: Do a focus session
1. Open **Focus**.
2. Add a **subject** (for example FAR, Tax) with *Add subject*, and give it a daily target if you like.
3. In the timer settings, set your focus length (for example 60 minutes) and a daily goal (for example 4 sessions).
4. Write one line for your **intention** ("IAS 16, questions 1 to 10").
5. Press **Start**.
6. When it rings, the alarm keeps going until you stop it. Then a full-screen question asks **"Did you do the work?"** about exactly what you wrote. Answer honestly. It goes in your log.

Want it stricter? Turn on **lock apps** so apps are paused during each session, or start a **focus block**: you commit until a set end time, sessions and breaks run by themselves, and leaving early takes your recovery code.

## Step 6: Read your log weekly
On **Focus**, open the log. You'll see a 14-day and 12-week chart, tap a point for that day's sessions, and hours by subject so a neglected subject shows. Use **Export** to save a CSV backup (do this weekly). **Import** restores it on a new phone.

## Step 7: Use the urge log when it hits
Go to **Settings → Urge log**.
1. Tap what set the urge off (bored, stressed, lonely, tired, anxious, habit, other).
2. Tap **I feel an urge** and wait the ten minutes. Stand up, drink water, walk to another room.
3. Tap **I got through it** or **I gave in**. Be honest. Nobody sees this.
4. Once a week, read **This week**: your most common trigger, the hour they cluster around, and days since you last gave in. Use it: if it's always 23:00 and bored, that's your Night detox window.
5. Optionally tap **Share with someone I trust**. It opens your phone's share sheet and only goes to whoever you pick. Nothing is sent otherwise.

## Step 8: The panic button
**Settings → Impulse lock** sets what happens when you press the panic button on the unlock screen: it locks the app, and any apps you choose, for a set time (15 minutes to 6 hours). Set it up on a calm day.

## Step 9: Block single apps by hand
On the **Apps** tab, flip the switch next to any app to pause it. Paused apps are greyed out, can't open, and their notifications are hidden. You can also set daily time limits and time-between-openings limits per app.

## Step 10: Check it's really working
Open **Settings → Status**. It shows whether device owner, exact alarms, battery optimization and notifications are all fine, with a **Fix** button for anything red. It also lists every blocked app and why. On Xiaomi phones also allow autostart and remove the battery limit for Déchaîner.

---

## If you get locked out
- **Lost your recovery code:** *Settings → Forced removal*. Start it, and after **4 days** you can remove everything without the code. The timer can't be sped up. This is the escape hatch, and it also works while a locked schedule is running.

## A simple daily routine
1. Morning: check today's schedule and pick your subject.
2. Study: 60-minute focus sessions, answer honestly.
3. When an urge hits: use the urge log instead of arguing with yourself.
4. Sunday: read your focus log and urge summary, export a backup, and adjust one schedule.

## Be realistic
This app removes access and adds friction. It doesn't fix boredom, stress or loneliness, and it can't touch your other devices. If phone or porn use is seriously hurting your life, pair the app with a friend, counsellor or doctor.
