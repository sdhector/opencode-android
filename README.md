# OpenCode Android — Session Groups and Follow Up

This is an unofficial fork of [OpenCode for Android](https://github.com/anomalyco/opencode-android). I started it to make a busy session drawer easier to navigate and to stop losing track of agent replies I’d read but still needed to act on. I’m sharing the changes so the Anomaly team can review the ideas and adopt whichever parts fit the official app.

- **Session groups and subgroups.** Organize conversations without changing their working directories. Drag sessions between groups, start a new session from a group, and swipe right to go back one level without closing the drawer.
- **Manual order or Last Activity.** Sort groups by recent activity without losing their saved manual order. Session indicators roll up to subgroups and parent groups.
- **Optional shared groups.** A compatible server plugin can share group membership across clients and let agents propose changes for approval. The plugin is not part of this fork.
- **Follow Up and Snooze.** The blue dot still means unread. A purple outlined dot marks a reply you’ve read but still need to act on. **Follow Up**, just below **Catch Up**, brings those sessions together. Mark a reminder Done or snooze it until tomorrow, next week, or a date you choose. Reminders stay on the device and don’t send push notifications.

The conversation started in [Anomaly’s session grouping issue #264](https://github.com/anomalyco/opencode-android/issues/264), which also has the original demo. For the official app and build instructions, see the [upstream README](https://github.com/anomalyco/opencode-android#readme).
