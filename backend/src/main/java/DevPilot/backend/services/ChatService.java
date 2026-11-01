package DevPilot.backend.services;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import DevPilot.backend.dto.ChatMessageResponse;
import DevPilot.backend.dto.ChatSessionResponse;
import DevPilot.backend.dto.CreateChatSessionRequest;
import DevPilot.backend.entity.ChatMessage;
import DevPilot.backend.entity.ChatSession;
import DevPilot.backend.entity.IndexStatus;
import DevPilot.backend.entity.MessageRole;
import DevPilot.backend.entity.Repository;
import DevPilot.backend.exceptions.BadRequestException;
import DevPilot.backend.exceptions.NotFoundException;
import DevPilot.backend.repository.ChatMessageRepository;
import DevPilot.backend.repository.ChatSessionRepository;
import DevPilot.backend.services.ai.ChatPromptBuilder;
import DevPilot.backend.services.ai.ChatStreamHandler;
import DevPilot.backend.services.ai.CitationMapper;
import DevPilot.backend.services.ai.CodeContextRetriever;

@Service
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RepoService repoService;
    private final CodeContextRetriever codeContextRetriever;
    private final ChatPromptBuilder chatPromptBuilder;
    private final ChatStreamHandler chatStreamHandler;
    private final CitationMapper citationMapper;

    // Explicit constructor replaces Lombok's @RequiredArgsConstructor
    public ChatService(
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            RepoService repoService,
            CodeContextRetriever codeContextRetriever,
            ChatPromptBuilder chatPromptBuilder,
            ChatStreamHandler chatStreamHandler,
            CitationMapper citationMapper) {

        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.repoService = repoService;
        this.codeContextRetriever = codeContextRetriever;
        this.chatPromptBuilder = chatPromptBuilder;
        this.chatStreamHandler = chatStreamHandler;
        this.citationMapper = citationMapper;
    }

    @Transactional
    public ChatSessionResponse createSession(UUID userId, CreateChatSessionRequest request) {
        Repository repo = repoService.requireOwned(request.repositoryId(), userId);

        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository must be indexed before chatting");
        }

        String title = request.title() != null && !request.title().isBlank()
                ? request.title()
                : "Chat with " + repo.getFullName();

        // Create ChatSession without Lombok's builder
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setRepositoryId(repo.getId());
        session.setTitle(title);

        session = chatSessionRepository.save(session);

        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(UUID userId, UUID repositoryId) {
        repoService.requireOwned(repositoryId, userId);

        return chatSessionRepository
                .findByUserIdAndRepositoryIdOrderByCreatedAtDesc(userId, repositoryId)
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(UUID userId, UUID sessionId) {
        ChatSession session = requireSession(userId, sessionId);

        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatSession requireSession(UUID userId, UUID sessionId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Chat session not found"));
    }

    public SseEmitter streamReply(UUID userId, UUID sessionId, String userContent) {

        // 1. Ensure the session exists and the repo is indexed
        ChatSession session = requireSession(userId, sessionId);
        Repository repo = repoService.requireOwned(session.getRepositoryId(), userId);

        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository is not ready for chat");
        }

        // 2. Persist the user's message without Lombok's builder
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(session.getId());
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(userContent);

        userMessage = chatMessageRepository.save(userMessage);

        // 3. RAG retrieval — find code chunks similar to the question
        var retrievedContext = codeContextRetriever.retrieve(repo.getId(), userContent);

        // 4. Build LLM prompts from retrieved context + question
        String systemPrompt = chatPromptBuilder.systemPrompt(repo.getFullName());
        String userPrompt = chatPromptBuilder.userPrompt(
                retrievedContext.contextText(), userContent);

        // 5. Stream OpenAI response to the client (SSE)
        return chatStreamHandler.stream(
                session.getId(),
                toMessageResponse(userMessage),
                retrievedContext.citations(),
                systemPrompt,
                userPrompt);
    }

    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getRepositoryId(),
                session.getTitle(),
                session.getCreatedAt());
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}