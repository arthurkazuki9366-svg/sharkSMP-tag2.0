package com.exemplo.tag;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TagPlugin extends JavaPlugin implements Listener {

    private static final String PREFIXO_TIME = "tg_";
    private static final Set<String> RESERVADAS =
            new HashSet<>(Arrays.asList("lista", "remover", "dar", "recarregar"));

    private static class Tag {
        String chave;
        String prefixo;
        String permissao;
        Team team;
    }

    private final Map<String, Tag> tags = new LinkedHashMap<>();
    private final Map<UUID, String> prefixos = new ConcurrentHashMap<>();
    private Scoreboard board;
    private Team timePadrao;
    private File dataFile;
    private YamlConfiguration data;

    private volatile boolean chatAtivo;
    private volatile String chatFormato = "";

    // ---------------- Ciclo de vida ----------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        montarTimes();
        aplicarTodos();
    }

    @Override
    public void onDisable() {
        if (board != null) limparTimes();
    }

    private String cor(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String chave, String padrao) {
        return cor(getConfig().getString("mensagens." + chave, padrao));
    }

    // ---------------- Times (tag em cima da cabeca + TAB) ----------------

    private void limparTimes() {
        for (Team t : new ArrayList<>(board.getTeams())) {
            if (t.getName().startsWith(PREFIXO_TIME)) t.unregister();
        }
    }

    private Team criarTime(int indice, String prefixo, String corNome) {
        Team t = board.registerNewTeam(String.format(PREFIXO_TIME + "%03d", indice));
        if (prefixo.length() > 64) prefixo = prefixo.substring(0, 64);
        t.setPrefix(prefixo);
        if (corNome != null && !corNome.isEmpty()) {
            try {
                ChatColor c = ChatColor.valueOf(corNome.toUpperCase());
                if (c.isColor()) t.setColor(c);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Cor invalida no config: " + corNome);
            }
        }
        return t;
    }

    private void montarTimes() {
        board = Bukkit.getScoreboardManager().getMainScoreboard();
        limparTimes();
        tags.clear();

        chatAtivo = getConfig().getBoolean("chat.ativar", false);
        chatFormato = getConfig().getString("chat.formato", "{tag}&f{player}&7: &f{msg}");

        ConfigurationSection sec = getConfig().getConfigurationSection("tags");
        int i = 0;
        if (sec != null) {
            for (String chave : sec.getKeys(false)) {
                String k = chave.toLowerCase();
                if (RESERVADAS.contains(k)) {
                    getLogger().warning("Nome de tag reservado, ignorando: " + chave);
                    continue;
                }
                if (i >= 998) break;

                Tag t = new Tag();
                t.chave = k;
                t.prefixo = cor(sec.getString(chave + ".prefixo", ""));
                t.permissao = sec.getString(chave + ".permissao", "");
                t.team = criarTime(i++, t.prefixo, sec.getString(chave + ".cor"));
                tags.put(k, t);
            }
        }
        timePadrao = criarTime(999, cor(getConfig().getString("sem-tag-prefixo", "")), null);
    }

    private boolean podeUsar(Player p, Tag t) {
        return t.permissao == null || t.permissao.isEmpty() || p.hasPermission(t.permissao);
    }

    private Tag resolver(Player p) {
        String base = "jogadores." + p.getUniqueId();
        String escolhida = data.getString(base + ".tag");
        if (escolhida != null) {
            Tag t = tags.get(escolhida);
            if (t != null && (data.getBoolean(base + ".forcada") || podeUsar(p, t))) return t;
        }
        if (getConfig().getBoolean("automatica", true)) {
            for (Tag t : tags.values()) {
                if (!t.permissao.isEmpty() && p.hasPermission(t.permissao)) return t;
            }
        }
        String padrao = getConfig().getString("padrao", "");
        if (padrao != null && !padrao.isEmpty()) return tags.get(padrao.toLowerCase());
        return null;
    }

    private void aplicar(Player p) {
        Tag tag = resolver(p);
        Team destino = tag == null ? timePadrao : tag.team;
        String nome = p.getName();

        Team atual = board.getEntryTeam(nome);
        if (atual != null && atual != destino && atual.getName().startsWith(PREFIXO_TIME)) {
            atual.removeEntry(nome);
        }
        if (!destino.hasEntry(nome)) destino.addEntry(nome);

        // O TAB usa o prefixo do time automaticamente; limpamos qualquer nome customizado
        p.setPlayerListName(null);
        prefixos.put(p.getUniqueId(), tag == null ? "" : tag.prefixo);
    }

    private void aplicarTodos() {
        for (Player p : Bukkit.getOnlinePlayers()) aplicar(p);
    }

    private void salvar() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Nao consegui salvar data.yml: " + e.getMessage());
        }
    }

    // ---------------- Eventos ----------------

    @EventHandler
    public void aoEntrar(PlayerJoinEvent e) {
        aplicar(e.getPlayer());
    }

    @EventHandler
    public void aoSair(PlayerQuitEvent e) {
        String nome = e.getPlayer().getName();
        Team t = board.getEntryTeam(nome);
        if (t != null && t.getName().startsWith(PREFIXO_TIME)) t.removeEntry(nome);
        prefixos.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void aoConversar(AsyncPlayerChatEvent e) {
        if (!chatAtivo) return;
        String prefixo = prefixos.getOrDefault(e.getPlayer().getUniqueId(), "").replace("%", "%%");
        String fmt = cor(chatFormato).replace("%", "%%")
                .replace("{tag}", prefixo)
                .replace("{player}", "%1$s")
                .replace("{msg}", "%2$s");
        e.setFormat(fmt);
    }

    // ---------------- Comandos ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /tag <nome> | /tag lista | /tag remover");
            return true;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("lista")) {
            sender.sendMessage(msg("lista-titulo", "&eTags disponiveis:"));
            for (Tag t : tags.values()) {
                if (sender instanceof Player && !podeUsar((Player) sender, t)) continue;
                sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + t.chave
                        + ChatColor.DARK_GRAY + " » " + t.prefixo);
            }
            return true;
        }

        if (sub.equals("recarregar")) {
            if (!sender.hasPermission("tag.admin")) {
                sender.sendMessage(msg("sem-permissao", "&cVoce nao tem permissao."));
                return true;
            }
            reloadConfig();
            montarTimes();
            aplicarTodos();
            sender.sendMessage(msg("recarregado", "&aConfig recarregada."));
            return true;
        }

        if (sub.equals("dar")) {
            if (!sender.hasPermission("tag.admin")) {
                sender.sendMessage(msg("sem-permissao", "&cVoce nao tem permissao."));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /tag dar <jogador> <tag|remover>");
                return true;
            }
            Player alvo = Bukkit.getPlayerExact(args[1]);
            if (alvo == null) {
                sender.sendMessage(msg("jogador-offline", "&cJogador nao encontrado."));
                return true;
            }
            String base = "jogadores." + alvo.getUniqueId();
            String nomeTag;
            if (args[2].equalsIgnoreCase("remover")) {
                data.set(base, null);
                nomeTag = "(nenhuma)";
            } else {
                Tag t = tags.get(args[2].toLowerCase());
                if (t == null) {
                    sender.sendMessage(msg("tag-inexistente", "&cEssa tag nao existe."));
                    return true;
                }
                data.set(base + ".tag", t.chave);
                data.set(base + ".forcada", true);
                nomeTag = t.prefixo.trim();
            }
            salvar();
            aplicar(alvo);
            sender.sendMessage(msg("dar-sucesso", "&aTag alterada.")
                    .replace("{jogador}", alvo.getName()).replace("{tag}", nomeTag));
            alvo.sendMessage(msg("dar-recebida", "&aSua tag foi alterada.")
                    .replace("{tag}", nomeTag));
            return true;
        }

        // A partir daqui so jogadores
        if (!(sender instanceof Player)) {
            sender.sendMessage(msg("so-jogador", "Apenas jogadores podem usar este comando."));
            return true;
        }
        Player p = (Player) sender;
        String base = "jogadores." + p.getUniqueId();

        if (sub.equals("remover")) {
            data.set(base, null);
            salvar();
            aplicar(p);
            p.sendMessage(msg("tag-removida", "&aSua tag foi removida."));
            return true;
        }

        Tag t = tags.get(sub);
        if (t == null) {
            p.sendMessage(msg("tag-inexistente", "&cEssa tag nao existe."));
            return true;
        }
        if (!podeUsar(p, t)) {
            p.sendMessage(msg("sem-permissao-tag", "&cVoce nao tem permissao para usar essa tag."));
            return true;
        }
        data.set(base + ".tag", t.chave);
        data.set(base + ".forcada", null);
        salvar();
        aplicar(p);
        p.sendMessage(msg("tag-definida", "&aSua tag agora e: &f{tag}").replace("{tag}", t.prefixo.trim()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 0) return out;
        String atual = args[args.length - 1].toLowerCase();

        if (args.length == 1) {
            List<String> base = new ArrayList<>(Arrays.asList("lista", "remover"));
            if (s.hasPermission("tag.admin")) {
                base.add("dar");
                base.add("recarregar");
            }
            for (Tag t : tags.values()) {
                if (!(s instanceof Player) || podeUsar((Player) s, t)) base.add(t.chave);
            }
            for (String b : base) if (b.startsWith(atual)) out.add(b);
        } else if (args[0].equalsIgnoreCase("dar") && s.hasPermission("tag.admin")) {
            if (args.length == 2) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(atual)) out.add(p.getName());
                }
            } else if (args.length == 3) {
                List<String> base = new ArrayList<>(tags.keySet());
                base.add("remover");
                for (String b : base) if (b.startsWith(atual)) out.add(b);
            }
        }
        return out;
    }
}
