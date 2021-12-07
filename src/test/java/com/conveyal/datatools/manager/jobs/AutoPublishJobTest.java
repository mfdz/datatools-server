package com.conveyal.datatools.manager.jobs;

import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.conveyal.datatools.DatatoolsTest;
import com.conveyal.datatools.UnitTest;
import com.conveyal.datatools.manager.auth.Auth0UserProfile;
import com.conveyal.datatools.manager.models.ExternalFeedSourceProperty;
import com.conveyal.datatools.manager.models.FeedSource;
import com.conveyal.datatools.manager.models.FeedVersion;
import com.conveyal.datatools.manager.models.Project;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.conveyal.datatools.TestUtils.createFeedVersion;
import static com.conveyal.datatools.TestUtils.createFeedVersionFromGtfsZip;
import static com.conveyal.datatools.TestUtils.zipFolderFiles;
import static com.conveyal.datatools.manager.extensions.mtc.MtcFeedResource.TEST_AGENCY;
import static com.conveyal.datatools.manager.models.FeedRetrievalMethod.FETCHED_AUTOMATICALLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the various {@link AutoPublishJob} cases.
 */
public class AutoPublishJobTest extends UnitTest {
    private static final String TEST_COMPLETED_FOLDER = "test-completed";
    private static final Auth0UserProfile user = Auth0UserProfile.createTestAdminUser();
    private static Project project;
    private static FeedSource feedSource;

    /**
     * Prepare and start a testing-specific web server
     */
    @BeforeAll
    public static void setUp() throws IOException {
        // start server if it isn't already running
        DatatoolsTest.setUp();

        // Create a project, feed sources, and feed versions to merge.
        project = new Project();
        project.name = String.format("Test %s", new Date());
        Persistence.projects.create(project);

        FeedSource fakeAgency = new FeedSource("Feed source", project.id, FETCHED_AUTOMATICALLY);
        Persistence.feedSources.create(fakeAgency);
        feedSource = fakeAgency;

        // Add an AgencyId entry to ExternalFeedSourceProperty
        // (one-time, it will be reused for this feed source)
        // but set the value to TEST_AGENCY to prevent actual S3 upload.
        ExternalFeedSourceProperty agencyIdProp = new ExternalFeedSourceProperty(
            feedSource,
            "MTC",
            "AgencyId",
            TEST_AGENCY
        );
        Persistence.externalFeedSourceProperties.create(agencyIdProp);
    }

    @AfterAll
    public static void tearDown() {
        if (project != null) {
            project.delete();
        }
    }

    /**
     * Ensures that a feed is or is not published depending on errors in the feed.
     */
    @ParameterizedTest
    @MethodSource("createPublishFeedCases")
    void shouldProcessFeed(String resourceName, boolean isError, String errorMessage) throws IOException {
        // Add the version to the feed source
        FeedVersion createdVersion;
        if (resourceName.endsWith(".zip")) {
            createdVersion = createFeedVersionFromGtfsZip(feedSource, resourceName);
        } else {
            createdVersion = createFeedVersion(feedSource, zipFolderFiles(resourceName));
        }

        // Create the job
        AutoPublishJob autoPublishJob = new AutoPublishJob(feedSource, user);

        // Run the job in this thread (we're not concerned about concurrency here).
        autoPublishJob.run();

        assertEquals(
            isError,
            autoPublishJob.status.error,
            "AutoPublish job error status was incorrectly determined."
        );

        if (isError) {
            assertEquals(errorMessage, autoPublishJob.status.message);
        }
    }

    private static Stream<Arguments> createPublishFeedCases() {
        return Stream.of(
            Arguments.of(
                "fake-agency-with-only-calendar-expire-in-2099-with-failed-referential-integrity",
                true,
                "Could not publish this feed version because it contains blocking errors."
            ),
            Arguments.of(
                "bart_old_lite.zip",
                true,
                "Could not publish this feed version because it contains GTFS+ blocking errors."
            ),
            Arguments.of(
                "bart_new_lite.zip",
                false,
                null
            )
        );
    }

    @Test
    void shouldUpdateFeedInfoAfterPublishComplete() {
        // Add the version to the feed source
        FeedVersion createdVersion = createFeedVersionFromGtfsZip(feedSource, "bart_new_lite.zip");

        // Create the job
        AutoPublishJob autoPublishJob = new AutoPublishJob(feedSource, user);

        // Run the job in this thread (we're not concerned about concurrency here).
        autoPublishJob.run();

        assertEquals(false, autoPublishJob.status.error);

        // Make sure that the publish-pending attribute has been set for the feed version in Mongo.
        FeedVersion updatedFeedVersion = Persistence.feedVersions.getById(createdVersion.id);
        assertNotNull(updatedFeedVersion.sentToExternalPublisher);

        // Create a test FeedUpdater instance, and simulate running the task.
        TestCompletedFeedRetriever completedFeedRetriever = new TestCompletedFeedRetriever();
        FeedUpdater feedUpdater = FeedUpdater.createForTest(completedFeedRetriever);

        // The list of feeds processed externally (completed) should be empty at this point.
        Map<String, String> etags = feedUpdater.checkForUpdatedFeeds();
        assertTrue(etags.isEmpty());

        // Simulate completion of feed publishing.
        completedFeedRetriever.isPublishingComplete = true;

        // The etags should contain the id of the agency.
        // If a feed has been republished since last check, it will have a new etag/file hash,
        // and the scenario below should apply.
        Map<String, String> etagsAfter = feedUpdater.checkForUpdatedFeeds();
        assertEquals(1, etagsAfter.size());
        assertTrue(etagsAfter.containsValue("test-etag"));

        // Make sure that the publish-complete attribute has been set for the feed version in Mongo.
        FeedVersion updatedFeedVersionAfter = Persistence.feedVersions.getById(createdVersion.id);
        Date updatedDate = updatedFeedVersionAfter.processedByExternalPublisher;
        String namespace = updatedFeedVersionAfter.namespace;
        assertNotNull(updatedDate);

        // At the next check for updates, the metadata for the feeds completed above
        // should not be updated again.
        feedUpdater.checkForUpdatedFeeds();
        FeedVersion updatedFeedVersionAfter2 = Persistence.feedVersions.getById(createdVersion.id);
        assertEquals(updatedDate, updatedFeedVersionAfter2.processedByExternalPublisher);
        assertEquals(namespace, updatedFeedVersionAfter2.namespace);
    }

    private static class TestCompletedFeedRetriever implements FeedUpdater.CompletedFeedRetriever {
        public boolean isPublishingComplete;

        @Override
        public List<S3ObjectSummary> retrieveCompletedFeeds() {
            if (!isPublishingComplete) {
                return new ArrayList<>();
            } else {
                S3ObjectSummary objSummary = new S3ObjectSummary();
                objSummary.setETag("test-etag");
                objSummary.setKey(String.format("%s/%s", TEST_COMPLETED_FOLDER, TEST_AGENCY));
                return Lists.newArrayList(objSummary);
            }
        }
    }
}
