package org.zalando.stups.fullstop.jobs.ami;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.zalando.stups.fullstop.aws.ClientProvider;
import org.zalando.stups.fullstop.jobs.common.AccountIdSupplier;
import org.zalando.stups.fullstop.jobs.common.FetchTaupageYaml;
import org.zalando.stups.fullstop.jobs.common.TaupageExpirationTimeProvider;
import org.zalando.stups.fullstop.jobs.config.JobsProperties;
import org.zalando.stups.fullstop.jobs.exception.JobExceptionHandler;
import org.zalando.stups.fullstop.violation.Violation;
import org.zalando.stups.fullstop.violation.ViolationSink;
import org.zalando.stups.fullstop.violation.service.ViolationService;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static com.amazonaws.regions.Region.getRegion;
import static com.google.common.collect.Sets.newHashSet;
import static java.time.format.DateTimeFormatter.ofPattern;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.zalando.stups.fullstop.violation.ViolationType.OUTDATED_TAUPAGE;

public class FetchAmiJobTest {

    private static final String ACCOUNT_1 = "111111111111";
    private static final String ACCOUNT_2 = "222222222222";
    private static final HashSet<String> ACCOUNTS = newHashSet(ACCOUNT_1, ACCOUNT_2);
    private static final String REGION_1 = "eu-west-1";
    private static final String REGION_2 = "us-east-1";
    private static final List<String> REGIONS = asList(REGION_1, REGION_2);
    private static final String INSTANCE_ID = "i-12345";
    private static final String IMAGE_ID = "ami-12345";
    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private ViolationSink mockViolationSink;
    @Mock
    private ClientProvider mockClientProvider;
    @Mock
    private AccountIdSupplier mockAccountIdSupplier;
    @Mock
    private JobsProperties mockJobsProperties;
    @Mock
    private ViolationService mockViolationService;
    @Mock
    private FetchTaupageYaml mockFetchTaupageYaml;
    @Mock
    private AmazonEC2Client mockEC2Client;
    @Mock
    private JobExceptionHandler mockExceptionHandler;
    @Mock
    private TaupageExpirationTimeProvider mockExpirationTimeProvider;

    private FetchAmiJob job;

    private Instance instance1 = new Instance()
            .withInstanceId(INSTANCE_ID)
            .withImageId(IMAGE_ID);

    @Before
    public void setUp() {
        job = new FetchAmiJob(mockViolationSink, mockClientProvider, mockAccountIdSupplier, mockJobsProperties, mockViolationService, mockFetchTaupageYaml, "Taupage-AMI-", ACCOUNT_1, mockExceptionHandler, mockExpirationTimeProvider);

        when(mockFetchTaupageYaml.getTaupageYaml(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @After
    public void tearDown() {
        verifyNoMoreInteractions(mockViolationSink, mockClientProvider, mockAccountIdSupplier, mockJobsProperties, mockViolationService, mockFetchTaupageYaml, mockEC2Client, mockExpirationTimeProvider);
    }

    @Test
    public void testInit() {
        job.init();
    }

    @Test
    public void testRunWithMultipleAccountsAndRegions() {
        final DescribeInstancesResult describeInstancesResult = new DescribeInstancesResult()
                .withReservations(new Reservation().withInstances(instance1));
        final Image image = new Image()
                .withImageId(IMAGE_ID)
                .withName("Taupage-AMI-" + LocalDate.now().format(ofPattern("yyyyMMdd")) + "-123456")
                .withOwnerId(ACCOUNT_1);
        when(mockExpirationTimeProvider.getExpirationTime(eq(REGION_1), anyString(), anyString())).thenReturn(ZonedDateTime.now().plusDays(10));
        when(mockExpirationTimeProvider.getExpirationTime(eq(REGION_2), anyString(), anyString())).thenReturn(null);
        when(mockAccountIdSupplier.get()).thenReturn(ACCOUNTS);
        when(mockJobsProperties.getWhitelistedRegions()).thenReturn(REGIONS);
        when(mockClientProvider.getClient(eq(AmazonEC2Client.class), anyString(), any())).thenReturn(mockEC2Client);
        when(mockEC2Client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(describeInstancesResult);
        when(mockEC2Client.describeImages(any(DescribeImagesRequest.class))).thenReturn(new DescribeImagesResult().withImages(image));

        job.run();

        verify(mockAccountIdSupplier).get();
        verify(mockJobsProperties).getWhitelistedRegions();

        REGIONS.forEach(regionName -> {

            verify(mockExpirationTimeProvider, times(ACCOUNTS.size())).getExpirationTime(eq(regionName), eq(ACCOUNT_1), eq(IMAGE_ID));

            ACCOUNTS.forEach(account -> {
                verify(mockClientProvider).getClient(eq(AmazonEC2Client.class), eq(account), eq(getRegion(Regions.fromName(regionName))));
                verify(mockViolationService).violationExists(eq(account), eq(regionName), eq(FetchAmiJob.EVENT_ID), eq(INSTANCE_ID), eq(OUTDATED_TAUPAGE));
            });
        });

        final int accountsTimesRegions = ACCOUNTS.size() * REGIONS.size();

        final ArgumentCaptor<DescribeInstancesRequest> describeInstances = ArgumentCaptor.forClass(DescribeInstancesRequest.class);
        verify(mockEC2Client, times(accountsTimesRegions)).describeInstances(describeInstances.capture());
        assertThat(describeInstances.getValue().getNextToken()).isNull();

        final ArgumentCaptor<DescribeImagesRequest> describeImages = ArgumentCaptor.forClass(DescribeImagesRequest.class);
        verify(mockEC2Client, times(accountsTimesRegions)).describeImages(describeImages.capture());
        assertThat(describeImages.getValue().getImageIds()).containsExactly(IMAGE_ID);
    }

    @Test
    public void testFindOutdatedTaupage() {
        final DescribeInstancesResult describeInstancesResult = new DescribeInstancesResult()
                .withReservations(new Reservation().withInstances(instance1));
        final Image image = new Image()
                .withImageId(IMAGE_ID)
                .withName("Taupage-AMI-" + LocalDate.now().minusDays(70).format(ofPattern("yyyyMMdd")) + "-123456")
                .withOwnerId(ACCOUNT_1);
        when(mockExpirationTimeProvider.getExpirationTime(anyString(), anyString(), anyString())).thenReturn(ZonedDateTime.now().minusDays(1));
        when(mockAccountIdSupplier.get()).thenReturn(singleton(ACCOUNT_1));
        when(mockJobsProperties.getWhitelistedRegions()).thenReturn(singletonList(REGION_1));
        when(mockClientProvider.getClient(eq(AmazonEC2Client.class), anyString(), any())).thenReturn(mockEC2Client);
        when(mockEC2Client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(describeInstancesResult);
        when(mockEC2Client.describeImages(any(DescribeImagesRequest.class))).thenReturn(new DescribeImagesResult().withImages(image));

        job.run();

        verify(mockExpirationTimeProvider).getExpirationTime(eq(REGION_1), eq(ACCOUNT_1), eq(IMAGE_ID));
        verify(mockAccountIdSupplier).get();
        verify(mockJobsProperties).getWhitelistedRegions();
        verify(mockClientProvider).getClient(eq(AmazonEC2Client.class), eq(ACCOUNT_1), eq(getRegion(Regions.fromName(REGION_1))));
        verify(mockViolationService).violationExists(eq(ACCOUNT_1), eq(REGION_1), eq(FetchAmiJob.EVENT_ID), eq(INSTANCE_ID), eq(OUTDATED_TAUPAGE));

        final ArgumentCaptor<DescribeInstancesRequest> describeInstances = ArgumentCaptor.forClass(DescribeInstancesRequest.class);
        verify(mockEC2Client).describeInstances(describeInstances.capture());
        assertThat(describeInstances.getValue().getNextToken()).isNull();

        final ArgumentCaptor<DescribeImagesRequest> describeImages = ArgumentCaptor.forClass(DescribeImagesRequest.class);
        verify(mockEC2Client).describeImages(describeImages.capture());
        assertThat(describeImages.getValue().getImageIds()).containsExactly(IMAGE_ID);

        verify(mockFetchTaupageYaml).getTaupageYaml(eq(INSTANCE_ID), eq(ACCOUNT_1), eq(REGION_1));
        final ArgumentCaptor<Violation> violation = ArgumentCaptor.forClass(Violation.class);
        verify(mockViolationSink).put(violation.capture());
        assertThat(violation.getValue())
                .extracting(
                        Violation::getAccountId,
                        Violation::getRegion,
                        Violation::getInstanceId,
                        Violation::getViolationType)
                .containsExactly(
                        ACCOUNT_1,
                        REGION_1,
                        INSTANCE_ID,
                        OUTDATED_TAUPAGE);

    }

    @Test
    public void testFollowPagination() {
        final DescribeInstancesResult result1 = new DescribeInstancesResult().withNextToken("123");
        final DescribeInstancesResult result2 = new DescribeInstancesResult().withNextToken("456");
        final DescribeInstancesResult result3 = new DescribeInstancesResult();

        when(mockAccountIdSupplier.get()).thenReturn(singleton(ACCOUNT_1));
        when(mockJobsProperties.getWhitelistedRegions()).thenReturn(singletonList(REGION_1));
        when(mockClientProvider.getClient(eq(AmazonEC2Client.class), anyString(), any())).thenReturn(mockEC2Client);
        when(mockEC2Client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(result1, result2, result3);

        job.run();

        verify(mockAccountIdSupplier).get();
        verify(mockJobsProperties).getWhitelistedRegions();
        verify(mockClientProvider).getClient(eq(AmazonEC2Client.class), eq(ACCOUNT_1), eq(getRegion(Regions.fromName(REGION_1))));


        final ArgumentCaptor<DescribeInstancesRequest> describeInstances = ArgumentCaptor.forClass(DescribeInstancesRequest.class);
        verify(mockEC2Client, times(3)).describeInstances(describeInstances.capture());
        assertThat(describeInstances.getAllValues()).extracting(DescribeInstancesRequest::getNextToken).containsExactly(null, "123", "456");

    }
}
