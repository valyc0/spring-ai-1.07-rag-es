package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agente: un ChatClient con il RAG registrato come tool ({@link RagTool}).
 * <p>
 * Il client e' costruito con {@code defaultTools}, quindi il tool e' disponibile in ogni prompt di
 * questo agente: non va dichiarato a ogni chiamata, e il modello lo usa solo se serve. Il ciclo e'
 * quello classico del function calling: il modello risponde con una tool call, Spring AI esegue il
 * tool e rimanda il risultato come messaggio tool, il modello usa quel testo come contesto e
 * risponde (o chiama un altro tool). Una domanda che l'indice non copre costa una sola chiamata
 * senza tool; una che ne copre piu' parti costa una ricerca per parte.
 * <p>
 * {@code ChatClient.Builder} e' prototype: il {@code defaultTools} di questo agente non finisce
 * sui client di {@code /chat} e {@code /search}, che restano senza tool.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /**
     * Diverse dal system prompt di {@code SearchService}: li' il contesto e' gia' nel prompt e il
     * modello deve solo risponderci, qui il contesto non c'e' e va CHIAMATO come tool. Quindi il
     * prompt dice quando cercare (e che si puo' ripetere la ricerca), non "rispondi solo col
     * contesto", che qui non esiste ancora.
     */
    private static final String SYSTEM_PROMPT = """
            Rispondi alle domande dell'utente usando l'indice documentale.
            Non usare conoscenze esterne all'indice e non inventare.

            Per ogni informazione che ti serve dall'indice chiama lo strumento
            search_knowledge_base: i testi che restituisce sono il tuo contesto. Puoi chiamarlo
            piu' volte con parole diverse o con filtri diversi (per esempio mode=lexical se
            l'utente cerca un codice esatto) finche' non hai quello che serve.
            Se lo strumento dice che non ha trovato nulla, dillo e non inventare.

            Cita sempre la fonte tra parentesi quadre, nella forma [source#chunkIndex].
            """;

    /**
     * Righe aggiunte quando la richiesta HTTP ha passato dei filtri. Diciarlo al modello serve per
     * due motivi: passa i filtri anche lui, quindi non torna a cercare senza, e non racconta di
     * aver cercato in una lingua o in un documento diverso da quello su cui sta rispondendo.
     */
    private static final String FORCED_PROMPT = """

            La ricerca e' ristretta a: %s.
            Passa questi filtri nelle chiamate a search_knowledge_base: sono gia' imposti
            dall'applicazione, quindi ometterli allargherebbe i risultati.
            """;

    private final ChatClient chatClient;

    public AgentService(ChatClient.Builder builder, RagTool ragTool) {
        this.chatClient = builder.defaultTools(ragTool).build();
    }

    /**
     * Una domanda all'agente: toolCalls elenca le ricerche fatte, in ordine.
     * <p>
     * {@code forced} sono i filtri della richiesta HTTP: finiscono nel toolContext, quindi il tool
     * li applica senza poter essere scavalcato dal modello, e finiscono anche nel prompt. Un
     * valore vuoto non e' un filtro (vedi {@link SearchFilters}), quindi non serve validare nulla.
     */
    public AgentResult ask(String question, SearchFilters forced) {
        // lista e filtri creati qui e passati nel toolContext: il toolContext e' per richiesta,
        // quindi il bean resta senza stato e due richieste in parallelo non si mescolano
        SearchFilters forcedFilters = forced == null ? emptyFilters() : forced;
        List<RagTool.SearchTrace> traces = new ArrayList<>();
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(RagTool.TRACE_KEY, traces);
        toolContext.put(RagTool.FORCED_KEY, forcedFilters);

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt(forcedFilters))
                .user(question)
                .toolContext(toolContext)
                .call()
                .chatResponse();
        String answer = response.getResult().getOutput().getText();
        log.info("agente: '{}', {} ricerche, forzati={} -> {}", question, traces.size(),
                RagTool.filtersSummary(forcedFilters), traces);
        return new AgentResult(answer, List.copyOf(traces));
    }

    /** Il prompt di base, piu' i filtri imposti dalla richiesta se ce ne sono. */
    static String systemPrompt(SearchFilters forced) {
        String filters = RagTool.filtersSummary(forced);
        return "-".equals(filters) ? SYSTEM_PROMPT : SYSTEM_PROMPT + String.format(FORCED_PROMPT, filters);
    }

    private static SearchFilters emptyFilters() {
        return new SearchFilters(null, null, null, null, null);
    }

    /** answer = risposta finale dell'agente; toolCalls = le ricerche RAG che ha deciso di fare. */
    public record AgentResult(String answer, List<RagTool.SearchTrace> toolCalls) {}
}
