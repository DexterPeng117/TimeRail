# TimeRail

> A visual code history plugin for IntelliJ IDEA — browse your full branching edit history as a tree and restore any past state with one click.

## What it does

TimeRail runs quietly in the background while you code. Every time your caret moves to a new line and you make an edit, it automatically captures a full screenshot of your editor and saves a complete snapshot of the file at that moment.

When you want to review your history, click **Open** to bring up a translucent overlay panel showing an interactive tree diagram of all your snapshots. Each card shows a live visual preview of the editor at that point in time. Click any card to instantly restore your file to that exact state — and if you keep coding after restoring, TimeRail automatically opens a new branch from that point rather than overwriting the original timeline.

## Features

- **Automatic snapshot capture** — triggered by line changes, not every keystroke (200ms debounce)
- **Branching history tree** — restoring a past snapshot spawns a new branch, so no history is ever lost
- **Visual tree UI** — translucent overlay panel with a horizontal tree diagram rendered inside IntelliJ
- **Editor screenshots** — each node displays a rendered preview of the editor at capture time
- **Zoom** — `+` / `-` / `1x` buttons in the title bar to zoom the tree in and out
- **One-click restore** — click any snapshot to rewrite the file back to that state; panel closes automatically
- **Lightweight controls** — Start / Stop / Clear / Open from a sidebar tool window

## Demo

![TimeRail Demo](assets/demo.gif)

## Tech Stack

- **Kotlin**
- **IntelliJ Platform SDK**
- **Gradle (Kotlin DSL)**
- **Swing / JBPopup** — custom-painted tree canvas with `Scrollable` interface for reliable zoomed scrolling

## Getting Started

1. Clone the repo
2. Open in IntelliJ IDEA
3. Run the Gradle `runIde` task to launch a sandboxed IDE with the plugin loaded
4. Open any file, click **Start Recording** in the TimeRail tool window, and start coding
5. Click **Open** to view your branching history tree

```bash
./gradlew runIde
```

> Requires Java 21. If you have multiple JDKs installed:
> ```bash
> export JAVA_HOME=$(/usr/libexec/java_home -v 21)
> ./gradlew runIde
> ```

## Project Structure

```
src/
└── main/
    ├── kotlin/com/timerail/timerail/
    │   └── TimeRailToolWindowFactory.kt   # Core plugin logic
    └── resources/META-INF/
        ├── plugin.xml                     # Plugin registration
        └── pluginIcon.svg                 # Plugin icon
```

## Author

**Dexter (Yulin) Peng**
Computer Science, University of Minnesota Twin Cities
[GitHub](https://github.com/DexterPeng117)
