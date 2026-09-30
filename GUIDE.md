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

## Step 11: The Urge Journal (a second app)
A companion app that sits next to Déchaîner. It's built from this same repository and signed with the same key.

**Install order matters:** install **Déchaîner first**, then the **Urge Journal**. Both must come from the same signed release build, or Android won't let them talk to each other.

**One-time setup in Déchaîner:** *Settings → Impulse lock*, set the panic button to "timer and suspend" and pick the apps to pause. The journal's "Block my phone" button uses that.

**When an urge hits:**
1. Tap the glowing button in the journal, or tap the **I feel an urge** quick-settings tile, or long-press the journal icon and pick it. That is the only thing you have to do. It immediately pauses your distraction apps for 30 minutes (the ones chosen in Déchaîner's Impulse lock) and starts a ten-minute **ride**: a slow breathing circle, one thing to do (by default, leave the room), and your own rule if you've written one. Nothing to answer.
2. When the ten minutes are up (or you feel through the worst of it), tell it how it stands: **It passed**, **Weaker, and I'm okay**, or **Still strong**. Tap what you tried. If it's still strong, ride another ten minutes or pause your apps for an hour.
3. Then choose **Save it and finish** (two taps) or **Add a few details** (three short questions and a plan). If you leave the app, a plain "Quick check-in" notification about 20 minutes later brings you back (you can turn it off in Settings), and a card on Home waits for you.
4. The coach learns from your check-ins: once a step has worked for you a few times, it moves to the top of your plans.

**Evening check-in:** in Settings turn on the evening reminder. Each evening Home asks "Did today go the way you planned?" with three answers, and the week panel shows how many days went to plan.

**Add the tile:** pull the notification shade down fully, tap the pencil or "Edit", find **I feel an urge**, and drag it to the top row.

**Make it your own:** after an urge, the plan screen has **Make it your own**. Write an "If ..., then ..." rule in your own words (the coach suggests wording you can tap in). Your rules are shown on later rides and plans, and you can edit or remove them in Settings. Rules you write yourself work better than ones handed to you.

**Patterns:** once you have at least six entries in a month, and most of them land in the same part of the day, Home offers a daily heads-up before that time, with a one-tap "pause my apps for an hour". It stays quiet until there is enough data to say something honest.

**If you already slipped:** tap **I already slipped**. No judgement. It asks what was in place and what would have stopped it, blocks your phone for the next stretch to stop the spiral, and suggests one change so it's less likely next time. Home counts **days without a slip out of the last 30** instead of a streak, so one slip costs one day, not everything.

**What if I slip during a focus block?** A block can't watch you or undo a slip; it removes the easy route while it runs, so the peak of an urge (usually ten minutes or so) passes without a door to walk through. It can be beaten: by an app you allowed (a browser is the usual leak, so keep browsers off the allowed list), by another device, or once the block ends. When it happens, tap **I already slipped** in the journal. It asks which of those it was, and turns the answer into one specific change. Also turn on **Private DNS** (Step 3), which filters every browser and app all day, not only during a block.

**The AI deep dive:** in the journal's **Settings**, pick a provider (Google AI Studio has a free tier; DeepSeek, OpenRouter, OpenAI and any OpenAI-compatible service also work), paste your key, and press **Test connection**. Use **Load models from my account** if a model name is rejected. Fill in **About you** with your goals and how blunt you want the coach to be; it is added to every deep dive.

**Weekly review:** once you have a few entries, a **Weekly review** button appears on Home. It looks at your counts and patterns (never your notes) and suggests what to try next.

**Backup:** your entries live only on the phone, and uninstalling erases them. In Settings, **Export** shares them as text you can keep, and **Import from clipboard** restores them.

Everything you write stays on your phone, except what you send for a deep dive or weekly review. The journal can only ask Déchaîner to block more, never to unblock.

## If you get locked out
- **Lost your recovery code:** *Settings → Forced removal*. Start it, and after **4 days** you can remove everything without the code. The timer can't be sped up. This is the escape hatch, and it also works while a locked schedule is running.

## A simple daily routine
1. Morning: check today's schedule and pick your subject.
2. Study: 60-minute focus sessions, answer honestly.
3. When an urge hits: use the urge log instead of arguing with yourself.
4. Sunday: read your focus log and urge summary, export a backup, and adjust one schedule.

## Be realistic
This app removes access and adds friction. It doesn't fix boredom, stress or loneliness, and it can't touch your other devices. If phone or porn use is seriously hurting your life, pair the app with a friend, counsellor or doctor.
