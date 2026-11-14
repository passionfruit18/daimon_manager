package com.daimon.api;

import com.daimon.api.Dtos.AppendMemoryRequest;
import com.daimon.api.Dtos.ChannelDto;
import com.daimon.api.Dtos.ChatRequest;
import com.daimon.api.Dtos.CommitDto;
import com.daimon.api.Dtos.CommitGraphDto;
import com.daimon.api.Dtos.CommitRequest;
import com.daimon.api.Dtos.CreateChannelRequest;
import com.daimon.api.Dtos.CreateInstanceRequest;
import com.daimon.api.Dtos.HealthDto;
import com.daimon.api.Dtos.HumanInputRequest;
import com.daimon.api.Dtos.InstanceDto;
import com.daimon.api.Dtos.InteractionDto;
import com.daimon.api.Dtos.MemoryRequest;
import com.daimon.api.Dtos.TeamRequest;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The daemon's local HTTP API, consumed by the Tauri UI. All routes live under {@code /api}. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DaemonResource {

    @Inject
    DaemonState state;

    @GET
    @Path("/health")
    public HealthDto health() {
        return new HealthDto("ok", DaemonState.VERSION, state.signer().publicKeyBase64(),
                state.config().geminiEnabled(), state.config().geminiVersion(), state.instanceCount(),
                state.store().size());
    }

    // ------------------------------------------------------------------ instances

    @GET
    @Path("/instances")
    public List<InstanceDto> instances() {
        return state.listInstances();
    }

    @POST
    @Path("/instances")
    public InstanceDto createInstance(CreateInstanceRequest request) throws IOException {
        require(request);
        return state.createInstance(request.name(), request.memory());
    }

    @GET
    @Path("/instances/{id}")
    public InstanceDto instance(@PathParam("id") String id) {
        return state.toDto(state.requireInstance(id));
    }

    @GET
    @Path("/instances/{id}/memory")
    public Map<String, String> memory(@PathParam("id") String id) {
        return Map.of("content", state.readMemory(id));
    }

    @PUT
    @Path("/instances/{id}/memory")
    public InstanceDto writeMemory(@PathParam("id") String id, MemoryRequest request) throws IOException {
        require(request);
        return state.writeMemory(id, request.content());
    }

    @POST
    @Path("/instances/{id}/memory")
    public InstanceDto appendMemory(@PathParam("id") String id, AppendMemoryRequest request) throws IOException {
        require(request);
        return state.appendMemory(id, request.entry());
    }

    /** Serializes the instance's workspace into a new signed commit (like {@code git commit -a}). */
    @POST
    @Path("/instances/{id}/commits")
    public CommitDto commit(@PathParam("id") String id, CommitRequest request) throws IOException {
        require(request);
        return state.commit(id, request.message(), request.author());
    }

    // ------------------------------------------------------------------ commits

    @GET
    @Path("/commits")
    public CommitGraphDto commits() {
        return state.graph();
    }

    @GET
    @Path("/commits/{ref}")
    public CommitDto commit(@PathParam("ref") String ref) {
        return state.commitDto(ref);
    }

    /** Spawns a new live instance from a commit or branch (like {@code git checkout} into a new worktree). */
    @POST
    @Path("/commits/{ref}/checkout")
    public InstanceDto checkout(@PathParam("ref") String ref) {
        return state.checkout(ref);
    }

    // ------------------------------------------------------------------ channels

    @GET
    @Path("/channels")
    public List<ChannelDto> channels() {
        return state.listChannels();
    }

    @POST
    @Path("/channels")
    public ChannelDto createChannel(CreateChannelRequest request) {
        require(request);
        return state.createChannel(request.name(), request.description());
    }

    /** Exposes a team of DAIMons on a channel. The name is given without the leading {@code #}. */
    @PUT
    @Path("/channels/{name}/team")
    public ChannelDto setTeam(@PathParam("name") String name, TeamRequest request) {
        require(request);
        return state.setTeam(name, request.instanceIds());
    }

    // ------------------------------------------------------------------ interactions

    @GET
    @Path("/interactions")
    public List<InteractionDto> interactions() {
        return state.listInteractions();
    }

    @POST
    @Path("/interactions/chat")
    public InteractionDto startChat(ChatRequest request) {
        require(request);
        return state.startChat(request);
    }

    @GET
    @Path("/interactions/{id}")
    public InteractionDto interaction(@PathParam("id") String id) {
        return state.interaction(id);
    }

    @POST
    @Path("/interactions/{id}/input")
    public InteractionDto humanInput(@PathParam("id") String id, HumanInputRequest request) {
        require(request);
        return state.submitHumanInput(id, request.author(), request.text());
    }

    @POST
    @Path("/interactions/{id}/cancel")
    public InteractionDto cancel(@PathParam("id") String id) {
        return state.cancel(id);
    }

    private static void require(Object body) {
        if (body == null) {
            throw new BadRequestException("Request body is required");
        }
    }
}
