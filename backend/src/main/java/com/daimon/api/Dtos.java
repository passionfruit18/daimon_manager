package com.daimon.api;

import java.util.List;
import java.util.Map;

/** JSON shapes exchanged with the desktop UI. Mirrored in {@code frontend/src/types.ts}. */
public final class Dtos {

    private Dtos() {
    }

    public record HealthDto(String status, String version, String signerPublicKey, boolean geminiEnabled,
            String geminiModel, int instances, int commits) {
    }

    public record InstanceDto(String id, String name, String implementationType, String workspace, String branch,
            String headHash, String createdFromHash, boolean changed, String memory, List<String> files,
            List<String> contracts) {
    }

    public record CommitDto(String hash, String shortHash, List<String> parents, String treeHash, String instanceName,
            String implementationType, String author, String message, String createdAt, boolean signed,
            boolean verified, List<String> branches, List<String> files) {
    }

    public record CommitGraphDto(List<CommitDto> commits, Map<String, String> branches) {
    }

    public record ChannelDto(String name, String description, List<String> teamIds, String createdAt) {
    }

    public record ParticipantDto(String id, String name, String role) {
    }

    public record LogEntryDto(long sequence, int turn, String kind, String speaker, String participantId,
            String roleName, String text, String timestamp) {
    }

    public record InteractionDto(String id, String title, String topic, String channel, String status, int turnLimit,
            int completedTurns, List<ParticipantDto> participants, List<LogEntryDto> log, String failureReason,
            String createdAt) {
    }

    // ------------------------------------------------------------------ requests

    public record CreateInstanceRequest(String name, String memory) {
    }

    public record MemoryRequest(String content) {
    }

    public record AppendMemoryRequest(String entry) {
    }

    public record CommitRequest(String message, String author) {
    }

    public record CreateChannelRequest(String name, String description) {
    }

    public record TeamRequest(List<String> instanceIds) {
    }

    public record ChatRequest(String topic, Integer turnLimit, List<String> participantIds, String channel,
            Integer turnDelayMs) {
    }

    public record HumanInputRequest(String author, String text) {
    }

    public record ErrorDto(String error, String message) {
    }
}
