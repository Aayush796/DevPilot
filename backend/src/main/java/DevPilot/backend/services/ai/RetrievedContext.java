package DevPilot.backend.services.ai;

import java.util.List;

import DevPilot.backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {
}