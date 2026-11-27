package com.multiship.backend.service.ai;

import com.multiship.backend.dto.ai.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Shipmate, the assistant panel in the workspace. Answers in plain text and
 * touches no data: it cannot read orders or create anything yet, and its
 * instructions make it say so instead of guessing.
 */
@Service
@RequiredArgsConstructor
public class ShipmateChatService {

    // ponytail: the conversation comes from the browser on every call and only the most
    // recent turns are sent on. Move to a stored chat memory when a conversation has to
    // survive a reload or be summarised instead of cut.
    static final int MAX_HISTORY_TURNS = 20;

    private static final String SYSTEM_PROMPT = """
        You are Shipmate, the assistant inside Multiship, a multi-carrier shipping
        platform that warehouse and e-commerce operators use to rate, label, track
        and customs-clear parcels with carriers such as UPS, FedEx, USPS, DHL,
        Royal Mail and DPD.

        The user is on the "%s" page of Multiship.

        What you can do today: answer questions about shipping, carriers and their
        services, customs (HS codes, incoterms, commercial invoices), packaging,
        addresses, and how shipping software like Multiship is used.

        What you cannot do yet: you cannot see this account's orders, rates,
        tracking, carriers or settings, and you cannot create, change or cancel
        anything. When asked for any of that, say so in one sentence, then say
        what the user can do on the page themselves.

        Rules:
        - Never invent a rate, price, transit time, tracking status, order or account detail.
        - If you are not sure, say so.
        - Lead with the answer and keep it short and practical.
        - Plain text only: no Markdown, no headings, no tables. Use short lines or simple dashes for lists.
        - Reply in the language the user writes in.
        """;

    private final OpenAiClient openAi;

    /**
     * The reply as a stream of text pieces. Throws up front (503) when the AI
     * is not configured, so the caller can answer with a normal error response.
     */
    public Flux<String> reply(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        List<ChatRequest.Turn> history = request.getHistory() == null ? List.of() : request.getHistory();
        for (ChatRequest.Turn turn : history.subList(Math.max(0, history.size() - MAX_HISTORY_TURNS), history.size())) {
            messages.add("assistant".equals(turn.getRole())
                    ? new AssistantMessage(turn.getText())
                    : new UserMessage(turn.getText()));
        }
        messages.add(new UserMessage(request.getMessage()));
        return openAi.streamText(SYSTEM_PROMPT.formatted(pageName(request.getPage())), messages, "chat");
    }

    /** The page name goes into the instructions, so only plain words survive. */
    private static String pageName(String page) {
        String clean = page == null ? "" : page.replaceAll("[^\\p{L}\\p{N} &/-]", "").trim();
        return StringUtils.hasText(clean) ? clean : "Multiship";
    }
}
