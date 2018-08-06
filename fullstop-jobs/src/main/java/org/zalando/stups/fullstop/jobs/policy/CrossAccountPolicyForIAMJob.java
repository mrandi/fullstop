package org.zalando.stups.fullstop.jobs.policy;

import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.identitymanagement.model.ListRolesRequest;
import com.amazonaws.services.identitymanagement.model.ListRolesResult;
import com.amazonaws.services.identitymanagement.model.Role;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zalando.stups.fullstop.aws.ClientProvider;
import org.zalando.stups.fullstop.jobs.FullstopJob;
import org.zalando.stups.fullstop.jobs.common.AccountIdSupplier;
import org.zalando.stups.fullstop.jobs.config.JobsProperties;
import org.zalando.stups.fullstop.jobs.exception.JobExceptionHandler;
import org.zalando.stups.fullstop.violation.Violation;
import org.zalando.stups.fullstop.violation.ViolationBuilder;
import org.zalando.stups.fullstop.violation.ViolationSink;

import javax.annotation.PostConstruct;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.amazonaws.regions.Region.getRegion;
import static com.amazonaws.regions.Regions.EU_WEST_1;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.trimToNull;
import static org.zalando.stups.fullstop.violation.ViolationType.CROSS_ACCOUNT_ROLE;

@Component
public class CrossAccountPolicyForIAMJob implements FullstopJob {


    private static final String EVENT_ID = "crossAccountPolicyForIAMJob";
    private static final Pattern ARN_PATTERN = Pattern.compile("^arn:aws:iam::(?<accountId>[0-9]{12}):.+$");

    private final Logger log = LoggerFactory.getLogger(CrossAccountPolicyForIAMJob.class);

    private final ViolationSink violationSink;

    private final ClientProvider clientProvider;

    private final AccountIdSupplier allAccountIds;

    private final JobsProperties jobsProperties;
    private final JobExceptionHandler jobExceptionHandler;

    @Autowired
    public CrossAccountPolicyForIAMJob(final ViolationSink violationSink,
                                       final ClientProvider clientProvider,
                                       final AccountIdSupplier allAccountIds,
                                       final JobsProperties jobsProperties,
                                       final JobExceptionHandler jobExceptionHandler) {
        this.violationSink = violationSink;
        this.clientProvider = clientProvider;
        this.allAccountIds = allAccountIds;
        this.jobsProperties = jobsProperties;
        this.jobExceptionHandler = jobExceptionHandler;
    }

    @PostConstruct
    public void init() {
        log.info("{} initalized", getClass().getSimpleName());
    }

    @Scheduled(
            fixedRate = 1000 * 60 * 150, // 2.5 hours
            initialDelay = 1000 * 60 * 15 // 15 minutes
    )
    public void run() {
        log.info("Running job {}", getClass().getSimpleName());
        for (final String account : allAccountIds.get()) {
            try {
                final AmazonIdentityManagementClient iamClient = clientProvider.getClient(
                        AmazonIdentityManagementClient.class,
                        account,
                        getRegion(EU_WEST_1)
                );

                Optional<String> nextMarker = Optional.empty();

                do {
                    final ListRolesRequest request = new ListRolesRequest();
                    nextMarker.ifPresent(request::setMarker);
                    final ListRolesResult listRolesResult = iamClient.listRoles(request);
                    nextMarker = Optional.ofNullable(trimToNull(listRolesResult.getMarker()));

                    for (final Role role : listRolesResult.getRoles()) {

                        final String assumeRolePolicyDocument = role.getAssumeRolePolicyDocument();

                        List<String> principals = Lists.newArrayList();
                        try {
                            principals = JsonPath.read(URLDecoder.decode(assumeRolePolicyDocument, "UTF-8"),
                                    ".Statement[*].Principal.AWS");
                        } catch (final UnsupportedEncodingException e) {
                            log.warn("Could not decode assumeRolePolicyDocument", e);
                        }

                        final Set<String> allowedAccounts = newHashSet(account, jobsProperties.getManagementAccount());
                        final List<String> crossAccountArns = principals.stream()
                                .map(ARN_PATTERN::matcher)
                                .filter(Matcher::matches)
                                .filter(m -> !allowedAccounts.contains(m.group("accountId")))
                                .map(Matcher::group)
                                .collect(toList());

                        if (crossAccountArns != null && !crossAccountArns.isEmpty()) {
                            writeViolation(
                                    account,
                                    ImmutableMap.of(
                                            "role_arn", role.getArn(),
                                            "role_name", role.getRoleName(),
                                            "grantees", crossAccountArns),
                                    role.getRoleId()
                            );
                        }
                    }

                } while (nextMarker.isPresent());
            } catch (Exception e) {
                jobExceptionHandler.onException(e, ImmutableMap.of(
                        "job", this.getClass().getSimpleName(),
                        "aws_account_id", account));
            }

        }

        log.info("Completed job {}", getClass().getSimpleName());
    }

    private void writeViolation(final String account, final Object metaInfo, final String roleId) {
        final ViolationBuilder violationBuilder = new ViolationBuilder();
        final Violation violation = violationBuilder.withAccountId(account)
                .withRegion(EU_WEST_1.getName())
                .withPluginFullyQualifiedClassName(CrossAccountPolicyForIAMJob.class)
                .withType(CROSS_ACCOUNT_ROLE)
                .withMetaInfo(metaInfo)
                .withInstanceId(roleId)
                .withEventId(EVENT_ID).build();
        violationSink.put(violation);
    }

}
