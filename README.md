# VoidSpace MC Bridge — main Paper server only

This is the **source project** for a Paper plugin. It is not a prebuilt JAR. It collects data only from the Paper server where it is installed and sends it to Supabase over HTTPS.

## Sends
- Online players and count (from `online_players`)
- Main-server join/leave events (`player_events`)
- Player username, LuckPerms primary group (if LuckPerms is installed), Vault economy balance, kills, deaths, playtime seconds (`player_stats`)

## Important compatibility note
The project targets Java 21 and the Paper API coordinate `26.3-R0.1-SNAPSHOT` based on the server version supplied by the owner. If Gradle reports that this Paper API version cannot be found, check the exact Minecraft/Paper API version reported by the server and update the dependency in `build.gradle` to a published API version. The plugin has not been tested on the owner's server yet.

## Setup
1. In Supabase Dashboard → SQL Editor, run `supabase/bridge-schema.sql`.
2. In Supabase project settings, copy the **service_role/secret key**. This key is powerful. Do not put it in Netlify, `src/config.js`, a public repository, screenshots, or messages.
3. Build the plugin JAR (see Build below).
4. Stop the **main Paper server**. Upload the built `VoidSpaceBridge-1.0.0.jar` to that server's `plugins/` folder. Do **not** put this Paper plugin in the Velocity proxy.
5. Start Paper once to generate `plugins/VoidSpaceBridge/config.yml`, then stop Paper.
6. Edit that config on the server: set `supabase.url` to `https://YOUR_PROJECT_REF.supabase.co`, paste the service-role key into `supabase.service-role-key`, and keep `server-id: main-paper`.
7. Start Paper. Run `/voidspacebridge status` as an operator. Check the Paper console for errors. `/voidspacebridge sync` requests a manual snapshot.
8. In Supabase Table Editor, check `player_stats`, `online_players`, and `player_events`.
9. The website must query these tables, and filter events/online data by `server_id = 'main-paper'`. If the existing frontend does not query these tables, it also needs a small frontend update.

## Build the JAR with GitHub Actions (easiest if you don't have Gradle)
1. Create a private GitHub repository and upload the contents of this folder (including `.github`).
2. Open the repository's **Actions** tab and run **Build VoidSpaceBridge** (or push to main).
3. Open the completed workflow run, download the artifact named `VoidSpaceBridge-1.0.0`, and extract it. The JAR is inside.

You can also build locally with Java 21 and Gradle installed: `gradle clean build`. Output: `build/libs/VoidSpaceBridge-1.0.0.jar`.

## Security and limitations
- The service-role key belongs only in the Paper server's private config file. Treat it like a password. Restrict panel file access and never share that config.
- Use HTTPS (the plugin requires an `https://` Supabase URL).
- Vault is used to read the economy balance. ExcellentEconomy must register a compatible Vault economy provider. If the console says `Vault provider not found`, verify Vault and the ExcellentEconomy bridge/provider setup.
- LuckPerms is optional; without it, rank is recorded as `member`.
- Statistics come from the main Paper server's Bukkit statistics. Historical/offline playtime may depend on whether the server has the player's stats files available.
- This is a first build and has not been tested against your exact server build or Supabase schema. Test on a backup/staging server first. Do not expose the service-role key.
- This plugin syncs online players and writes stats periodically; player_events are written for Paper join/quit events only. It does not track joins to the Velocity network or other Paper servers.
