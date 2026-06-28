# CLI Reference

The `oryxos` command-line tool provides 12 sub-commands for managing the workspace, profiles, and running agents.

## Startup and status

```bash
oryxos init                      # Initialize .oryxos/ workspace
oryxos status                    # Show configuration and runtime status
oryxos chat [--profile <name>]   # Interactive multi-turn conversation (default profile: default)
oryxos serve [--port 8080]       # Start HTTP API server
oryxos gateway                   # Daemon mode (multi-channel)
```

## Profile management

```bash
oryxos profile list
oryxos profile create <name>
oryxos profile show <name>
oryxos profile delete <name>
```

## Queries

```bash
oryxos provider list             # List configured providers and their status
oryxos tool list                 # List available tools from the registry
oryxos session list              # List active sessions
```

## Global flags

| Flag | Description |
|------|-------------|
| `--profile <name>` | Specify a profile (default: `default`) |
| `--workspace <path>` | Override the workspace path (default: `./.oryxos`) |
| `--log-level <level>` | Set log level: `debug`, `info`, `warn`, `error` |
