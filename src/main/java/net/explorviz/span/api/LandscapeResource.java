package net.explorviz.span.api;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import net.explorviz.span.landscape.Landscape;
import net.explorviz.span.landscape.assembler.LandscapeAssembler;
import net.explorviz.span.landscape.assembler.LandscapeAssemblyException;
import net.explorviz.span.landscape.assembler.impl.NoRecordsException;
import net.explorviz.span.landscape.loader.LandscapeLoader;
import net.explorviz.span.landscape.loader.LandscapeRecord;
import net.explorviz.span.persistence.PersistenceSpan;
import net.explorviz.span.trace.Trace;
import net.explorviz.span.trace.TraceLoader;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

@Path("/v2/landscapes")
@Produces(MediaType.APPLICATION_JSON)
public class LandscapeResource {
  @Inject
  public LandscapeLoader landscapeLoader;

  @Inject
  public LandscapeAssembler landscapeAssembler;

  @Inject
  public TraceLoader traceLoader;

  @GET
  @Path("/{token}/structure")
  @Operation(summary = "Retrieve a landscape graph",
      description = "Assembles the (possibly empty) landscape of "
          + "all spans observed in the given time range")
  @APIResponses(@APIResponse(responseCode = "200", description = "Success",
      content = @Content(mediaType = "application/json",
          schema = @Schema(implementation = Landscape.class))))
  public Uni<Landscape> getStructure(@PathParam("token") final String token,
      @QueryParam("from") final Long from, @QueryParam("to") final Long to) {
    final Multi<LandscapeRecord> recordMulti;
    if (from == null || to == null) {
      // TODO: Cache (shared with PersistenceSpanProcessor?)
      recordMulti = landscapeLoader.loadLandscape(parseUuid(token));
    } else {
      // TODO: Remove millisecond/nanosecond mismatch hotfix
      recordMulti = landscapeLoader.loadLandscape(parseUuid(token),
          from * 1_000_000L, to * 1_000_000L);
    }

    return recordMulti.collect().asList()
        .map(landscapeAssembler::assembleFromRecords)
        .onFailure(NoRecordsException.class)
        .transform(t -> new NotFoundException("Landscape not found or empty", t))
        .onFailure(LandscapeAssemblyException.class)
        .transform(t ->
            new InternalServerErrorException("Landscape assembly error: " + t.getMessage(), t));
  }

  @GET
  @Path("/{token}/dynamic")
  public Multi<Trace> getDynamic(@PathParam("token") final String token,
      @QueryParam("from") final Long from, @QueryParam("to") final Long to) {
    if (from == null || to == null) {
      throw new BadRequestException("from and to are required");
    }

    // TODO: Remove millisecond/nanosecond mismatch hotfix
    return traceLoader.loadTraces(parseUuid(token), from * 1_000_000L, to * 1_000_000L);
  }

  @GET
  @Path("/{token}/dynamic/{traceid}")
  public Uni<Trace> getDynamicTrace(@PathParam("token") final String token,
      @PathParam("traceid") final Long traceId) {
    return traceLoader.loadTrace(parseUuid(token), traceId);
  }

  private UUID parseUuid(final String token) {
    // TODO: Remove invalid token hotfix
    if ("mytokenvalue".equals(token)) {
      return PersistenceSpan.DEFAULT_UUID;
    }

    try {
      return UUID.fromString(token);
    } catch (final IllegalArgumentException e) {
      throw new BadRequestException("Invalid token", e);
    }
  }
}
