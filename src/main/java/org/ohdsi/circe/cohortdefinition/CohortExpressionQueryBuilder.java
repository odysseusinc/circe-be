/*
 *
 * Copyright 2017 Observational Health Data Sciences and Informatics
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Authors: Chris Knoll, Gowtham Rao
 *
 */
package org.ohdsi.circe.cohortdefinition;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.ohdsi.circe.cohortdefinition.builders.*;
import org.ohdsi.circe.helper.ResourceHelper;
import org.ohdsi.circe.vocabulary.ConceptSetExpressionQueryBuilder;

import static org.ohdsi.circe.cohortdefinition.builders.BuilderUtils.buildDateRangeClause;
import static org.ohdsi.circe.cohortdefinition.builders.BuilderUtils.buildNumericRangeClause;
import static org.ohdsi.circe.cohortdefinition.builders.BuilderUtils.dateStringToSql;
import static org.ohdsi.circe.cohortdefinition.builders.BuilderUtils.getConceptIdsFromConcepts;

/**
 *
 * @author cknoll1
 */
public class CohortExpressionQueryBuilder implements IGetCriteriaSqlDispatcher, IGetEndStrategySqlDispatcher {

  private final static ConceptSetExpressionQueryBuilder conceptSetQueryBuilder = new ConceptSetExpressionQueryBuilder();
  private final static String CODESET_QUERY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/codesetQuery.sql");

  private final static String COHORT_QUERY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/generateCohort.sql");

  private final static String PRIMARY_EVENTS_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/primaryEventsQuery.sql");

  private final static String WINDOWED_CRITERIA_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/windowedCriteria.sql");
  private final static String ADDITIONAL_CRITERIA_INNER_TEMPLATE = StringUtils.replace(ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/additionalCriteriaInclude.sql"), "@windowedCriteria", WINDOWED_CRITERIA_TEMPLATE);
  private final static String ADDITIONAL_CRITERIA_LEFT_TEMPLATE = StringUtils.replace(ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/additionalCriteriaExclude.sql"), "@windowedCriteria", WINDOWED_CRITERIA_TEMPLATE);
  private final static String GROUP_QUERY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/groupQuery.sql");

  private final static String INCLUSION_RULE_QUERY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/inclusionrule.sql");
  private final static String INCLUSION_RULE_TEMP_TABLE_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/inclusionRuleTempTable.sql");
  private final static String CENSORING_QUERY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/censoringInsert.sql");

  private final static String EVENT_TABLE_EXPRESSION_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/eventTableExpression.sql");
  private final static String DEMOGRAPHIC_CRITERIA_QUERY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/demographicCriteria.sql");

  private final static String COHORT_INCLUSION_ANALYSIS_TEMPALTE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/cohortInclusionAnalysis.sql");
  private final static String COHORT_CENSORED_STATS_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/cohortCensoredStats.sql");

  // Strategy templates
  private final static String DATE_OFFSET_STRATEGY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/dateOffsetStrategy.sql");
  private final static String CUSTOM_ERA_STRATEGY_TEMPLATE = ResourceHelper.GetResourceAsString("/resources/cohortdefinition/sql/customEraStrategy.sql");

  private final static String DEFAULT_DRUG_EXPOSURE_END_DATE_EXPRESSION = "COALESCE(DRUG_EXPOSURE_END_DATE, DATEADD(day,DAYS_SUPPLY,DRUG_EXPOSURE_START_DATE), DATEADD(day,1,DRUG_EXPOSURE_START_DATE))";
  
  // Builders
  private final static ConditionOccurrenceSqlBuilder<ConditionOccurrence> conditionOccurrenceSqlBuilder = new ConditionOccurrenceSqlBuilder<>();
  private final static DeathSqlBuilder<Death> deathSqlBuilder = new DeathSqlBuilder<>();
  private final static DeviceExposureSqlBuilder<DeviceExposure> deviceExposureSqlBuilder = new DeviceExposureSqlBuilder<>();
  private final static DoseEraSqlBuilder<DoseEra> doseEraSqlBuilder = new DoseEraSqlBuilder<>();
  private final static DrugEraSqlBuilder<DrugEra> drugEraSqlBuilder = new DrugEraSqlBuilder<>();
  private final static DrugExposureSqlBuilder<DrugExposure> drugExposureSqlBuilder = new DrugExposureSqlBuilder<>();
  private final static LocationRegionSqlBuilder<LocationRegion> locationRegionSqlBuilder = new LocationRegionSqlBuilder<>();
  private final static MeasurementSqlBuilder<Measurement> measurementSqlBuilder = new MeasurementSqlBuilder<>();
  private final static ObservationPeriodSqlBuilder<ObservationPeriod> observationPeriodSqlBuilder = new ObservationPeriodSqlBuilder<>();
  private final static ObservationSqlBuilder<Observation> observationSqlBuilder = new ObservationSqlBuilder<>();
  private final static PayerPlanPeriodSqlBuilder<PayerPlanPeriod> payerPlanPeriodSqlBuilder = new PayerPlanPeriodSqlBuilder<>();
  private final static ProcedureOccurrenceSqlBuilder<ProcedureOccurrence> procedureOccurrenceSqlBuilder = new ProcedureOccurrenceSqlBuilder<>();
  private final static SpecimenSqlBuilder<Specimen> specimenSqlBuilder = new SpecimenSqlBuilder<>();
  private final static VisitOccurrenceSqlBuilder<VisitOccurrence> visitOccurrenceSqlBuilder = new VisitOccurrenceSqlBuilder<>();
  private final static VisitDetailSqlBuilder<VisitDetail> visitDetailSqlBuilder = new VisitDetailSqlBuilder<>();
  private final static ConditionEraSqlBuilder<ConditionEra> conditionEraSqlBuilder = new ConditionEraSqlBuilder<>();
  private final static String DEFAULT_COHORT_ID_FIELD_NAME = "cohort_definition_id";

  public static class BuildExpressionQueryOptions {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @JsonProperty("cohortIdFieldName")
    public String cohortIdFieldName;

    @JsonProperty("cohortId")
    public Integer cohortId;

    @JsonProperty("cdmSchema")
    public String cdmSchema;

    @JsonProperty("targetTable")
    public String targetTable;

    @JsonProperty("resultSchema")
    public String resultSchema;

    @JsonProperty("vocabularySchema")
    public String vocabularySchema;

    @JsonProperty("generateStats")
    public boolean generateStats;
    
    @JsonProperty("retainCohortCovariates")
    public boolean retainCohortCovariates;

    public static CohortExpressionQueryBuilder.BuildExpressionQueryOptions fromJson(String json) {
      try {
        CohortExpressionQueryBuilder.BuildExpressionQueryOptions options
                = JSON_MAPPER.readValue(json, CohortExpressionQueryBuilder.BuildExpressionQueryOptions.class);
        return options;
      } catch (Exception e) {
        throw new RuntimeException("Error parsing expression query options", e);
      }
    }
  }

  private String getOccurrenceOperator(int type) {
    // Occurance check { id: 0, name: 'Exactly', id: 1, name: 'At Most' }, { id: 2, name: 'At Least' }
    switch (type) {
      case 0:
        return "=";
      case 1:
        return "<=";
      case 2:
        return ">=";
    }

    throw new RuntimeException(String.format("Invalid occurrene operator recieved: type=%d.",type));

  }
  
  private String getAdditionalColumns(List<CriteriaColumn> columns, String prefix) {
    return String.join(",", columns.stream().map((column) -> { return prefix + column.columnName();}).collect(Collectors.toList()));
  }

  private String wrapCriteriaQuery(String query, CriteriaGroup group, Boolean useDatetime, BuilderOptions options,
          Criteria criteria) {
    String eventQuery = StringUtils.replace(EVENT_TABLE_EXPRESSION_TEMPLATE, "@eventQuery", query);

    ArrayList<String> selectColsPE = new ArrayList<>();
    if (options.isRetainCohortCovariates()) {
		  eventQuery = StringUtils.replace(eventQuery, "@concept_id", ", Q.concept_id");
      eventQuery = criteria.embedWrapCriteriaQuery(eventQuery, selectColsPE);
    } else {
      eventQuery = StringUtils.replace(eventQuery, "@concept_id", "");
      eventQuery = StringUtils.replace(eventQuery, "@QAdditionalColumnsInclusionN", "");
    }

    String groupQuery = this.getCriteriaGroupQuery(group, String.format("(%s)", eventQuery), useDatetime,
            options.isRetainCohortCovariates());
    groupQuery = StringUtils.replace(groupQuery, "@indexId", "" + 0);
    String wrappedQuery = String.format(
            "select PE.person_id, PE.event_id" +
              "@concept_id" +
              "@PEAdditionalColumnsInclusionN" +
              ", PE.start_date, PE.end_date, PE.visit_occurrence_id, PE.sort_date FROM (\n%s\n) PE\nJOIN (\n%s) AC on AC.person_id = pe.person_id and AC.event_id = pe.event_id\n",
            query, groupQuery);

    // Add the fields concept_id, value_as_number, value_as_string, value_as_concept_id, unit_concept_id if the save covariates checkbox is checked
    if (options.isRetainCohortCovariates()) {
      wrappedQuery = StringUtils.replace(wrappedQuery, "@concept_id", ", PE.concept_id");
      wrappedQuery = StringUtils.replace(wrappedQuery, "@PEAdditionalColumnsInclusionN", StringUtils.join(selectColsPE, ""));
    } else {
      wrappedQuery = StringUtils.replace(wrappedQuery, "@concept_id", "");
      wrappedQuery = StringUtils.replace(wrappedQuery, "@PEAdditionalColumnsInclusionN", "");
    }
    return wrappedQuery;
  }

  public String getCodesetQuery(ConceptSet[] conceptSets) {

    if (conceptSets == null || conceptSets.length <= 0) {
      return StringUtils.replace(
              CODESET_QUERY_TEMPLATE,
              "@codesetInserts",
              StringUtils.EMPTY
      );
    }

    String unionSelectsQuery = Arrays.stream(conceptSets)
            .map(cs -> String.format("SELECT %d as codeset_id, c.concept_id FROM (%s) C", cs.id, conceptSetQueryBuilder.buildExpressionQuery(cs.expression)))
            .collect(Collectors.joining(" UNION ALL \n"));

    String queryWithInsert = StringUtils.replace(
            CODESET_QUERY_TEMPLATE,
            "@codesetInserts",
            "INSERT INTO #Codesets (codeset_id, concept_id)\n" + unionSelectsQuery + ";"
    );
    return queryWithInsert;

  }

  private String getCensoringEventsQuery(Criteria[] censoringCriteria, BuilderOptions builderOptions) {
    ArrayList<String> criteriaQueries = new ArrayList<>();
    for (Criteria c : censoringCriteria) {
      String criteriaQuery = c.accept(this, builderOptions);
      criteriaQueries.add(StringUtils.replace(CENSORING_QUERY_TEMPLATE, "@criteriaQuery", criteriaQuery));
    }

    return StringUtils.join(criteriaQueries, "\nUNION ALL\n");
  }

  public String getPrimaryEventsQuery(PrimaryCriteria primaryCriteria, BuilderOptions builderOptions) {
    String query = PRIMARY_EVENTS_TEMPLATE;

    ArrayList<String> criteriaQueries = new ArrayList<>();

    for (Criteria c : primaryCriteria.criteriaList) {
      criteriaQueries.add(c.accept(this, builderOptions));
    }

    query = StringUtils.replace(query, "@criteriaQueries", StringUtils.join(criteriaQueries, "\nUNION ALL\n"));

    ArrayList<String> primaryEventsFilters = new ArrayList<>();
    primaryEventsFilters.add(String.format(
            "DATEADD(day,%d,OP.OBSERVATION_PERIOD_START_DATE) <= E.START_DATE AND DATEADD(day,%d,E.START_DATE) <= OP.OBSERVATION_PERIOD_END_DATE",
            primaryCriteria.observationWindow.priorDays,
            primaryCriteria.observationWindow.postDays
    )
    );

    query = StringUtils.replace(query, "@primaryEventsFilter", StringUtils.join(primaryEventsFilters, " AND "));

    query = StringUtils.replace(query, "@EventSort", (primaryCriteria.primaryLimit.type != null && primaryCriteria.primaryLimit.type.equalsIgnoreCase("LAST")) ? "DESC" : "ASC");
    query = StringUtils.replace(query, "@primaryEventLimit", (!primaryCriteria.primaryLimit.type.equalsIgnoreCase("ALL") ? "WHERE P.ordinal = 1" : ""));

    return query;
  }

  public String getFinalCohortQuery(Period censorWindow) {

    String query = "select @target_cohort_id as @cohort_id_field_name, person_id, @start_date, @end_date \n"
            + "FROM #final_cohort CO";

    String startDate = "start_date";
    String endDate = "end_date";

    if (censorWindow != null && (censorWindow.startDate != null || censorWindow.endDate != null)) {
      if (censorWindow.startDate != null) {
        String censorStartDate = dateStringToSql(censorWindow.startDate);
        startDate = "CASE WHEN start_date > " + censorStartDate + " THEN start_date ELSE " + censorStartDate + " END";
      }
      if (censorWindow.endDate != null) {
        String censorEndDate = dateStringToSql(censorWindow.endDate);
        endDate = "CASE WHEN end_date < " + censorEndDate + " THEN end_date ELSE " + censorEndDate + " END";
      }
      query += "\nWHERE @start_date <= @end_date";
    }

    query = StringUtils.replace(query, "@start_date", startDate);
    query = StringUtils.replace(query, "@end_date", endDate);

    return query;
  }

  private String getInclusionRuleTableSql(CohortExpression expression) {
    String EMPTY_TABLE = "CREATE TABLE #inclusion_rules (rule_sequence int);";
    if (expression.inclusionRules.size() == 0 ) return EMPTY_TABLE;
    
    String UNION_TEMPLATE = "SELECT CAST(%d as int) as rule_sequence";
    List<String> unionList = IntStream.range(0,expression.inclusionRules.size())
            .mapToObj(i -> (String)String.format(UNION_TEMPLATE, i))
            .collect(Collectors.toList());
    
    return StringUtils.replace(INCLUSION_RULE_TEMP_TABLE_TEMPLATE, "@inclusionRuleUnions", StringUtils.join(unionList, " UNION ALL "));
  }
  private String getInclusionAnalysisQuery(String eventTable, int modeId) {
    String resultSql = COHORT_INCLUSION_ANALYSIS_TEMPALTE;
    resultSql = StringUtils.replace(resultSql, "@inclusionImpactMode", Integer.toString(modeId));
    resultSql = StringUtils.replace(resultSql, "@eventTable", eventTable);
    return resultSql;
  }

	// Function all full field select for union all
    private void addInclusionGroup(List<List<ColumnFieldData>> listFields, CriteriaGroup cg, int indexCG,
			List<String> inclusionRuleInsertN, List<String> inclusionRuleGroupN) {
        
        listFields.forEach(l -> {
            if (listFields.indexOf(l) == indexCG) {
                l.forEach(s -> {
                    inclusionRuleInsertN.add(", " + s.getName() + " " + s.getName() + "_" + indexCG);
                    inclusionRuleGroupN.add(", AC." + s.getName());
                });
            } else {
              l.forEach(s -> inclusionRuleInsertN
                      .add(", CAST(null as " + s.getDataType().getType() + ") " + s.getName() + "_"
                              + listFields.indexOf(l)));
          }
        });
	}

  public String buildExpressionQuery(String expression, BuildExpressionQueryOptions options) {
    return this.buildExpressionQuery(CohortExpression.fromJson(expression), options);
  }

  public String buildExpressionQuery(CohortExpression expression, BuildExpressionQueryOptions options) {
    String resultSql = COHORT_QUERY_TEMPLATE;

    String codesetQuery = getCodesetQuery(expression.conceptSets);
    resultSql = StringUtils.replace(resultSql, "@codesetQuery", codesetQuery);

    BuilderOptions builderOptions = new BuilderOptions();
    builderOptions.setUseDatetime(expression.useDatetime);
    builderOptions.setRetainCohortCovariates(options != null && options.retainCohortCovariates);
    String primaryEventsQuery = getPrimaryEventsQuery(expression.primaryCriteria, builderOptions);
    resultSql = StringUtils.replace(resultSql, "@primaryEventsQuery", primaryEventsQuery);

    String additionalCriteriaQuery = "";
    if (expression.additionalCriteria != null && !expression.additionalCriteria.isEmpty()) {
      CriteriaGroup acGroup = expression.additionalCriteria;
      String acGroupQuery = this.getCriteriaGroupQuery(acGroup, String.format("(%s)", primaryEventsQuery), expression.useDatetime, options != null && options.retainCohortCovariates);//acGroup.accept(this);
      acGroupQuery = StringUtils.replace(acGroupQuery, "@indexId", "" + 0);
      additionalCriteriaQuery = "\nJOIN (\n" + acGroupQuery + ") AC on AC.person_id = pe.person_id and AC.event_id = pe.event_id\n";
    }
    resultSql = StringUtils.replace(resultSql, "@additionalCriteriaQuery", additionalCriteriaQuery);

    resultSql = StringUtils.replace(resultSql, "@QualifiedEventSort", (expression.qualifiedLimit.type != null && expression.qualifiedLimit.type.equalsIgnoreCase("LAST")) ? "DESC" : "ASC");

    // Only apply qualified limit filter if additional criteria is specified.
    if (expression.additionalCriteria != null && expression.qualifiedLimit.type != null && !expression.qualifiedLimit.type.equalsIgnoreCase("ALL")) {
      resultSql = StringUtils.replace(resultSql, "@QualifiedLimitFilter", "WHERE QE.ordinal = 1");
    } else {
      resultSql = StringUtils.replace(resultSql, "@QualifiedLimitFilter", "");
    }

    // List store all field if need in list inclusion for UNION ALL
    List<List<ColumnFieldData>> listField = new ArrayList<>();

    if (expression.inclusionRules.size() > 0) {
      ArrayList<String> inclusionRuleInserts = new ArrayList<>();
      ArrayList<String> inclusionRuleTempTables = new ArrayList<>();

      // Add column field needed to listField
      for (int i = 0; i < expression.inclusionRules.size(); i++) {
          CriteriaGroup cg = expression.inclusionRules.get(i).expression;
          if (cg.criteriaList == null || cg.criteriaList.length == 0) {
              listField.add(new ArrayList<>());
          }
          for (CorelatedCriteria cc : cg.criteriaList) {
              List<ColumnFieldData> fieldDatas = cc.criteria.getSelectedField(builderOptions);
              listField.add(fieldDatas);
          }
      }      

      for (int i = 0; i < expression.inclusionRules.size(); i++) {
        ArrayList<String> lstFieldRuleInsertNValues = new ArrayList<>();
        CriteriaGroup cg = expression.inclusionRules.get(i).expression;
        String inclusionRuleInsert = getInclusionRuleQuery(cg, expression.useDatetime,
                options != null && options.retainCohortCovariates);
        inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@inclusion_rule_id", "" + i);
        inclusionRuleTempTables.add(String.format("#Inclusion_%d", i));

        if (options != null && options.retainCohortCovariates) {
          ArrayList<String> inclusionRuleInsertN = new ArrayList<>();
          ArrayList<String> inclusionRuleGroupN = new ArrayList<>();
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@conceptid", ", pe.concept_id");

          this.addInclusionGroup(listField, cg, i, inclusionRuleInsertN, inclusionRuleGroupN);
          
          lstFieldRuleInsertNValues.add(StringUtils.join(inclusionRuleInsertN, ""));
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@additionalColumnsInclusionN", StringUtils.join(lstFieldRuleInsertNValues, " "));
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@additionalColumnsCriteriaQuery", StringUtils.join(inclusionRuleGroupN, " "));
        } else {
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@concept_id", "");
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@additionalColumnsInclusionN", "");
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@conceptid", "");
          inclusionRuleInsert = StringUtils.replace(inclusionRuleInsert, "@additionalColumnsCriteriaQuery", "");
        }
        inclusionRuleInserts.add(inclusionRuleInsert);
      }

      String irTempUnion = "";
      if (options != null && options.retainCohortCovariates) {
        irTempUnion = inclusionRuleTempTables.stream()
          .map(d -> String.format("select %s.* \n" +
            "from %s \n", d, d))
          .collect(Collectors.joining("\nUNION ALL\n"));
        inclusionRuleInserts.add(String.format("SELECT * INTO #inclusion_events\nFROM (%s) I;", irTempUnion));

      } else {
        irTempUnion = inclusionRuleTempTables.stream()
          .map(d -> String.format("select inclusion_rule_id, person_id, event_id from %s", d))
          .collect(Collectors.joining("\nUNION ALL\n"));
        inclusionRuleInserts.add(String.format("SELECT inclusion_rule_id, person_id, event_id\nINTO #inclusion_events\nFROM (%s) I;", irTempUnion));

      }

      inclusionRuleInserts.addAll(inclusionRuleTempTables.stream()
              .map(d -> String.format("-- TRUNCATE TABLE %s;\nDROP TABLE %s;\n", d, d))
              .collect(Collectors.toList())
      );
      resultSql = StringUtils.replace(resultSql, "@inclusionCohortInserts", StringUtils.join(inclusionRuleInserts, "\n"));
    } else {
      resultSql = StringUtils.replace(resultSql, "@inclusionCohortInserts", "create table #inclusion_events (inclusion_rule_id bigint,\n\tperson_id bigint,\n\tevent_id bigint\n);");
    }

    resultSql = StringUtils.replace(resultSql, "@IncludedEventSort", (expression.expressionLimit.type != null && expression.expressionLimit.type.equalsIgnoreCase("LAST")) ? "DESC" : "ASC");

    if (expression.expressionLimit.type != null && !expression.expressionLimit.type.equalsIgnoreCase("ALL")) {
      resultSql = StringUtils.replace(resultSql, "@ResultLimitFilter", "WHERE Results.ordinal = 1");
    } else {
      resultSql = StringUtils.replace(resultSql, "@ResultLimitFilter", "");
    }

    resultSql = StringUtils.replace(resultSql, "@ruleTotal", String.valueOf(expression.inclusionRules.size()));

    ArrayList<String> endDateSelects = new ArrayList<>();

    if (!(expression.endStrategy instanceof DateOffsetStrategy)) {
      endDateSelects.add("-- By default, cohort exit at the event's op end date\nselect event_id, person_id, op_end_date as end_date from #included_events");
    }

    if (expression.endStrategy != null) {
      // replace @strategy_ends placeholders with temp table creation and cleanup scripts.
      resultSql = StringUtils.replace(resultSql, "@strategy_ends_temp_tables", expression.endStrategy.accept(this, "#included_events", options != null && options.retainCohortCovariates));
      resultSql = StringUtils.replace(resultSql, "@strategy_ends_cleanup", "TRUNCATE TABLE #strategy_ends;\nDROP TABLE #strategy_ends;\n");
      resultSql = StringUtils.replace(resultSql, "@paramEraStrategy", ", era_end_date");
      resultSql = StringUtils.replace(resultSql, "@insertEraStrategy", ", se.end_date");
      endDateSelects.add(String.format("-- End Date Strategy\n%s\n", "SELECT event_id, person_id, end_date from #strategy_ends"));
      if (options != null && options.retainCohortCovariates) {
        resultSql = StringUtils.replace(resultSql, "@strategy_ends_columns", ", se.end_date strategy_end_date");
        resultSql = StringUtils.replace(resultSql, "@leftjoinEraStrategy", "left join strategy_ends se on qe.concept_id = se.concept_id");
      }
    } else {
      // replace @trategy_ends placeholders with empty string
      resultSql = StringUtils.replace(resultSql, "@strategy_ends_temp_tables", "");
      resultSql = StringUtils.replace(resultSql, "@strategy_ends_cleanup", "");
      resultSql = StringUtils.replace(resultSql, "@paramEraStrategy", "");
      resultSql = StringUtils.replace(resultSql, "@insertEraStrategy", "");
    }
    resultSql = StringUtils.replace(resultSql, "@leftjoinEraStrategy", "");
    resultSql = StringUtils.replace(resultSql, "@strategy_ends_columns", "");

    if (expression.censoringCriteria != null && expression.censoringCriteria.length > 0) {
      endDateSelects.add(String.format("-- Censor Events\n%s\n", getCensoringEventsQuery(expression.censoringCriteria, builderOptions)));
    }

    resultSql = StringUtils.replace(resultSql, "@finalCohortQuery", getFinalCohortQuery(expression.censorWindow));

    resultSql = StringUtils.replace(resultSql, "@cohort_end_unions", StringUtils.join(endDateSelects, "\nUNION ALL\n"));

    if (!StringUtils.isEmpty(Integer.toString(expression.collapseSettings.eraPad)) && (expression.collapseSettings.eraPadUnit == null || IntervalUnit.DAY.getName().equals(expression.collapseSettings.eraPadUnit))) {
      resultSql = StringUtils.replace(resultSql, "@eraconstructorpad", Integer.toString(expression.collapseSettings.eraPad));
      resultSql = StringUtils.replace(resultSql, "@era_pad_unit", expression.collapseSettings.eraPadUnit);
    } else {
      resultSql = StringUtils.replace(resultSql, "@eraconstructorpad", Integer.toString(expression.collapseSettings.eraPadUnitValue));
      resultSql = StringUtils.replace(resultSql, "@era_pad_unit", expression.collapseSettings.eraPadUnit);
    }
    resultSql = StringUtils.replace(resultSql, "@inclusionRuleTable", getInclusionRuleTableSql(expression));
    resultSql = StringUtils.replace(resultSql, "@inclusionImpactAnalysisByEventQuery", getInclusionAnalysisQuery("#qualified_events", 0));
    resultSql = StringUtils.replace(resultSql, "@inclusionImpactAnalysisByPersonQuery", getInclusionAnalysisQuery("#best_events", 1));

    resultSql = StringUtils.replace(resultSql, "@cohortCensoredStatsQuery",
            (expression.censorWindow != null && (!StringUtils.isEmpty(expression.censorWindow.startDate) || !StringUtils.isEmpty(expression.censorWindow.endDate)))
                    ? COHORT_CENSORED_STATS_TEMPLATE
                    : "");

    if (options != null) {
      // replease query parameters with tokens
      if (options.cdmSchema != null) {
        resultSql = StringUtils.replace(resultSql, "@cdm_database_schema", options.cdmSchema);
      }
      if (options.targetTable != null) {
        resultSql = StringUtils.replace(resultSql, "@target_database_schema.@target_cohort_table", options.targetTable);
      }
      if (options.resultSchema != null) {
        resultSql = StringUtils.replace(resultSql, "@results_database_schema", options.resultSchema);
      }
      if (options.vocabularySchema != null) {
        resultSql = StringUtils.replace(resultSql, "@vocabulary_database_schema", options.vocabularySchema);
      } else if (options.cdmSchema != null) {
        resultSql = StringUtils.replace(resultSql, "@vocabulary_database_schema", options.cdmSchema);
      }
      if (options.cohortId != null) {
        resultSql = StringUtils.replace(resultSql, "@target_cohort_id", options.cohortId.toString());
      }

      resultSql = StringUtils.replace(resultSql, "@generateStats", options.generateStats ? "1" : "0");

      if (options.cohortIdFieldName != null) {
        resultSql = StringUtils.replaceAll(resultSql, "@cohort_id_field_name", options.cohortIdFieldName);
      } else {
        resultSql = StringUtils.replaceAll(resultSql, "@cohort_id_field_name", DEFAULT_COHORT_ID_FIELD_NAME);
      }
    } else {
      resultSql = StringUtils.replaceAll(resultSql, "@cohort_id_field_name", DEFAULT_COHORT_ID_FIELD_NAME);
    }

    if (options != null && options.retainCohortCovariates) {
      resultSql = StringUtils.replace(resultSql, "@concept_id", ", concept_id");
      resultSql = StringUtils.replace(resultSql, "@Qconcept_id", ", Q.concept_id");
      resultSql = StringUtils.replace(resultSql, "@pe_concept_id", ", pe.concept_id");
      
      List<String> allInclusionColumnsInserts = buildAllInclusionColumnsInserts(listField);
      resultSql = StringUtils.replace(resultSql, "@allInclusionColumnsInserts", StringUtils.join(allInclusionColumnsInserts, " "));
    } else {
      resultSql = StringUtils.replace(resultSql, "@conceptid", "");
      resultSql = StringUtils.replace(resultSql, "@concept_id", "");
      resultSql = StringUtils.replace(resultSql, "@Qconcept_id", "");
      resultSql = StringUtils.replace(resultSql, "@pe_concept_id", "");
      resultSql = StringUtils.replace(resultSql, "@allInclusionColumnsInserts", "");
    }

    return resultSql;
  }

  private List<String> buildAllInclusionColumnsInserts(List<List<ColumnFieldData>> listField) {
      List<String> result = new ArrayList<>();
      listField.forEach(l -> {
          if (l.size() > 0) {
              String field = "";
              for (ColumnFieldData s : l) {
                  field = field + ", " + s.getName() + "_" + listField.indexOf(l);
              }
              result.add(field);
          }
      });
      return result;
  }

  public String getCriteriaGroupQuery(CriteriaGroup group, String eventTable, Boolean useDatetime, Boolean retainCohortCovariates) {
    String query = GROUP_QUERY_TEMPLATE;
    ArrayList<String> additionalCriteriaQueries = new ArrayList<>();
    String joinType = "INNER";

    int indexId = 0;
    for (CorelatedCriteria cc : group.criteriaList) {
        String acQuery = this.getCorelatedlCriteriaQuery(cc, eventTable, useDatetime, retainCohortCovariates); // ac.accept(this);
      acQuery = StringUtils.replace(acQuery, "@indexId", "" + indexId);
      additionalCriteriaQueries.add(acQuery);
      indexId++;

      if (retainCohortCovariates) {
          query = cc.criteria.embedCriteriaGroup(query);
      } else {
          query = StringUtils.replace(query, "@e.additonColumns", "");
          query = StringUtils.replace(query, "@additonColumnsGroup", "");
      }
    }

    for (DemographicCriteria dc : group.demographicCriteriaList) {
      // the Demographics Criteria refers to an event date/datetime alias start_date, end_date
      // therefore the useDatetime logic is irrelevant at this place!?
      String dcQuery = this.getDemographicCriteriaQuery(dc, eventTable); //ac.accept(this);
      dcQuery = StringUtils.replace(dcQuery, "@indexId", "" + indexId);
      additionalCriteriaQueries.add(dcQuery);
      indexId++;
    }

    for (CriteriaGroup g : group.groups) {
        String gQuery = this.getCriteriaGroupQuery(g, eventTable, useDatetime, retainCohortCovariates); // g.accept(this);
      gQuery = StringUtils.replace(gQuery, "@indexId", "" + indexId);
      additionalCriteriaQueries.add(gQuery);
      indexId++;
    }

    if (!group.isEmpty())
    {
      query = StringUtils.replace(query, "@criteriaQueries", StringUtils.join(additionalCriteriaQueries, "\nUNION ALL\n"));

      String occurrenceCountClause = "HAVING COUNT(index_id) ";
      if (group.type.equalsIgnoreCase("ALL")) // count must match number of criteria + sub-groups in group.
      {
        occurrenceCountClause += "= " + indexId;
      }

      if (group.type.equalsIgnoreCase("ANY")) // count must be > 0 for an 'ANY' criteria
      {
        occurrenceCountClause += "> 0";
      }

      if (group.type.toUpperCase().startsWith("AT_")) {
        if (group.type.toUpperCase().endsWith("LEAST")) { // AT_LEAST
          occurrenceCountClause += ">= " + group.count;
        } else { // AT_MOST, which includes zero
          occurrenceCountClause += "<= " + group.count;
          joinType = "LEFT";
        }

        if (group.count == 0) { //if you are looking for a zero count within an AT_LEAST/AT_MOST, you need to do a left join
          joinType = "LEFT";
        }
      }

      query = StringUtils.replace(query, "@occurrenceCountClause", occurrenceCountClause);
      query = StringUtils.replace(query, "@joinType", joinType);
    } else // query group is empty so replace group query with a friendly default
    {
      query = "-- Begin Criteria Group\n select @indexId as index_id, person_id, event_id FROM @eventTable\n-- End Criteria Group\n";
    }

    query = StringUtils.replace(query, "@eventTable", eventTable);

    //If it does not exist group.criteriaList, remove it
    query = StringUtils.replace(query, "@e.additonColumns", "");
    query = StringUtils.replace(query, "@additonColumnsGroup", "");

    return query;
  }

  private String getInclusionRuleQuery(CriteriaGroup inclusionRule, Boolean useDatetime,
          Boolean retainCohortCovariates) {
    String resultSql = INCLUSION_RULE_QUERY_TEMPLATE;
      String additionalCriteriaQuery = "\nJOIN (\n"
              + getCriteriaGroupQuery(inclusionRule, "#qualified_events", useDatetime, retainCohortCovariates)
              + ") AC on AC.person_id = pe.person_id AND AC.event_id = pe.event_id";
    additionalCriteriaQuery = StringUtils.replace(additionalCriteriaQuery, "@indexId", "" + 0);
    resultSql = StringUtils.replace(resultSql, "@additionalCriteriaQuery", additionalCriteriaQuery);
    return resultSql;
  }

  public String getDemographicCriteriaQuery(DemographicCriteria criteria, String eventTable) {
    String query = DEMOGRAPHIC_CRITERIA_QUERY_TEMPLATE;
    query = StringUtils.replace(query, "@eventTable", eventTable);

    ArrayList<String> whereClauses = new ArrayList<>();

    // Age
    if (criteria.age != null) {
      whereClauses.add(buildNumericRangeClause("YEAR(E.start_date) - P.year_of_birth", criteria.age));
    }

    // Gender
    if (criteria.gender != null && criteria.gender.length > 0) {
      whereClauses.add(String.format("P.gender_concept_id in (%s)", StringUtils.join(getConceptIdsFromConcepts(criteria.gender), ",")));
    }

    // Race
    if (criteria.race != null && criteria.race.length > 0) {
      whereClauses.add(String.format("P.race_concept_id in (%s)", StringUtils.join(getConceptIdsFromConcepts(criteria.race), ",")));
    }

    // Race
    if (criteria.race != null && criteria.race.length > 0) {
      whereClauses.add(String.format("P.race_concept_id in (%s)", StringUtils.join(getConceptIdsFromConcepts(criteria.race), ",")));
    }

    // Ethnicity
    if (criteria.ethnicity != null && criteria.ethnicity.length > 0) {
      whereClauses.add(String.format("P.ethnicity_concept_id in (%s)", StringUtils.join(getConceptIdsFromConcepts(criteria.ethnicity), ",")));
    }

    // occurrenceStartDate
    if (criteria.occurrenceStartDate != null) {
      whereClauses.add(buildDateRangeClause("E.start_date", criteria.occurrenceStartDate));
    }

    // occurrenceEndDate
    if (criteria.occurrenceEndDate != null) {
      whereClauses.add(buildDateRangeClause("E.end_date", criteria.occurrenceEndDate));
    }

    if (whereClauses.size() > 0) {
      query = StringUtils.replace(query, "@whereClause", "WHERE " + StringUtils.join(whereClauses, " AND "));
    } else {
      query = StringUtils.replace(query, "@whereClause", "");
    }

    return query;
  }

  public String getWindowedCriteriaQuery(String sqlTemplate, WindowedCriteria criteria, String eventTable, BuilderOptions options) {

    String query = sqlTemplate;
    boolean checkObservationPeriod = !criteria.ignoreObservationPeriod;

    String criteriaQuery = criteria.criteria.accept(this, options);
    query = StringUtils.replace(query, "@criteriaQuery", criteriaQuery);
    query = StringUtils.replace(query, "@eventTable", eventTable);
    if (options != null && options.additionalColumns.size() > 0) {
      query = StringUtils.replace(query, "@additionalColumns", ", " + getAdditionalColumns(options.additionalColumns, "A."));
    } else {
      query = StringUtils.replace(query, "@additionalColumns", "");
    }

    if (options != null && options.isRetainCohortCovariates()) {
        query = criteria.criteria.embedWindowedCriteriaQuery(query);
        query = criteria.criteria.embedWindowedCriteriaQueryP(query);
    } else {
        query = StringUtils.replace(query, "@additionColumnscc", "");
        query = StringUtils.replace(query, "@p.additionColumns", "");
    }

    // build index date window expression
    String startExpression;
    String endExpression;
    List<String> clauses = new ArrayList<>();
    if (checkObservationPeriod) {
      clauses.add("A.START_DATE >= P.OP_START_DATE AND A.START_DATE <= P.OP_END_DATE");
    }

    // StartWindow
    Window startWindow = criteria.startWindow;
    String startIndexDateExpression = (startWindow.useIndexEnd != null && startWindow.useIndexEnd) ? "P.END_DATE" : "P.START_DATE";
    String startEventDateExpression = (startWindow.useEventEnd != null && startWindow.useEventEnd) ? "A.END_DATE" : "A.START_DATE";
    if (startWindow.start.days != null && (startWindow.start.timeUnit == null || startWindow.start.timeUnit.equals(IntervalUnit.DAY.getName()))) {
          startExpression = String.format("DATEADD(day,%d,%s)", startWindow.start.coeff * startWindow.start.days, startIndexDateExpression);
    } else if (startWindow.start.timeUnitValue != null) {
          startExpression = String.format("DATEADD(%s,%d,%s)", startWindow.start.timeUnit, startWindow.start.coeff * startWindow.start.timeUnitValue, startIndexDateExpression);
    } else {
          startExpression = checkObservationPeriod ? (startWindow.start.coeff == -1 ? "P.OP_START_DATE" : "P.OP_END_DATE") : null;
    }

    if (startExpression != null) {
      clauses.add(String.format("%s >= %s", startEventDateExpression, startExpression));
    }

    if (startWindow.end.days != null && (startWindow.end.timeUnit == null || IntervalUnit.DAY.getName().equals(startWindow.end.timeUnit))) {
      endExpression = String.format("DATEADD(day,%d,%s)", startWindow.end.coeff * startWindow.end.days, startIndexDateExpression);
    }else if(startWindow.end.timeUnitValue != null){
      endExpression = String.format("DATEADD(%s,%d,%s)", startWindow.end.timeUnit, startWindow.end.coeff * startWindow.end.timeUnitValue, startIndexDateExpression);
    }
    else {
      endExpression = checkObservationPeriod ? (startWindow.end.coeff == -1 ? "P.OP_START_DATE" : "P.OP_END_DATE") : null;
    }

    if (endExpression != null) {
      clauses.add(String.format("%s <= %s", startEventDateExpression, endExpression));
    }

      // EndWindow
    Window endWindow = criteria.endWindow;

    if (endWindow != null) {
      String endIndexDateExpression = (endWindow.useIndexEnd != null && endWindow.useIndexEnd) ? "P.END_DATE" : "P.START_DATE";
      // for backwards compatability, having a null endWindow.useIndexEnd means they SHOULD use the index end date.
      String endEventDateExpression = (endWindow.useEventEnd == null || endWindow.useEventEnd) ? "A.END_DATE" : "A.START_DATE";
      if (endWindow.start.days != null && (endWindow.start.timeUnit == null || IntervalUnit.DAY.getName().equals(endWindow.start.timeUnit))) {
        startExpression = String.format("DATEADD(day,%d,%s)", endWindow.start.coeff * endWindow.start.days, endIndexDateExpression);
      }else if(endWindow.start.timeUnitValue != null){
        startExpression = String.format("DATEADD(%s,%d,%s)", endWindow.start.timeUnit, endWindow.start.coeff * endWindow.start.timeUnitValue, endIndexDateExpression);
      }
      else {
        startExpression = checkObservationPeriod ? (endWindow.start.coeff == -1 ? "P.OP_START_DATE" : "P.OP_END_DATE") : null;
      }

      if (startExpression != null) {
        clauses.add(String.format("%s >= %s", endEventDateExpression, startExpression));
      }

      if (endWindow.end.days != null && (endWindow.end.timeUnit == null || IntervalUnit.DAY.getName().equals(endWindow.end.timeUnit))) {
        endExpression = String.format("DATEADD(day,%d,%s)", endWindow.end.coeff * endWindow.end.days, endIndexDateExpression);
      }
      else if(endWindow.end.timeUnitValue != null){
        endExpression = String.format("DATEADD(%s,%d,%s)", endWindow.end.timeUnit, endWindow.end.coeff * endWindow.end.timeUnitValue, endIndexDateExpression);
      }
      else {
        endExpression = checkObservationPeriod ? (endWindow.end.coeff == -1 ? "P.OP_START_DATE" : "P.OP_END_DATE") : null;
      }

      if (endExpression != null) {
        clauses.add(String.format("%s <= %s", endEventDateExpression, endExpression));
      }
    }

    // RestrictVisit
    boolean restrictVisit = criteria.restrictVisit;
    if (restrictVisit) {
      clauses.add("A.visit_occurrence_id = P.visit_occurrence_id");
    }

    query = StringUtils.replace(query, "@windowCriteria", clauses.size() > 0 ? " AND " + StringUtils.join(clauses, " AND ") : "");

    return query;
  }

    public String getWindowedCriteriaQuery(WindowedCriteria criteria, String eventTable) {
    String query = getWindowedCriteriaQuery(WINDOWED_CRITERIA_TEMPLATE, criteria, eventTable, null);
    return query;
  }

  public String getWindowedCriteriaQuery(WindowedCriteria criteria, String eventTable, BuilderOptions options) {
    String query = getWindowedCriteriaQuery(WINDOWED_CRITERIA_TEMPLATE, criteria, eventTable, options);
    return query;
  }

  public String getCorelatedlCriteriaQuery(CorelatedCriteria corelatedCriteria, String eventTable, Boolean useDatetime,
          Boolean retainCohortCovariates) {

    // pick the appropraite query template that is optimized for include (at least 1) or exclude (allow 0)
    String query = (corelatedCriteria.occurrence.type == Occurrence.AT_MOST || corelatedCriteria.occurrence.count == 0) ? ADDITIONAL_CRITERIA_LEFT_TEMPLATE : ADDITIONAL_CRITERIA_INNER_TEMPLATE;

    String countColumnExpression = "cc.event_id";

    BuilderOptions builderOptions = new BuilderOptions();
    // a part of the Cohort Expression design being passed
    builderOptions.setUseDatetime(useDatetime);
    if (corelatedCriteria.occurrence.isDistinct) {
      if (corelatedCriteria.occurrence.countColumn == null) { // backwards compatability:  default column uses domain_concept_id
        builderOptions.additionalColumns.add(CriteriaColumn.DOMAIN_CONCEPT);
        countColumnExpression = String.format("cc.%s", CriteriaColumn.DOMAIN_CONCEPT.columnName());
      } else {
        builderOptions.additionalColumns.add(corelatedCriteria.occurrence.countColumn);
        countColumnExpression = String.format("cc.%s", corelatedCriteria.occurrence.countColumn.columnName());
        
      }
    
    }
    builderOptions.setRetainCohortCovariates(retainCohortCovariates);
    query = getWindowedCriteriaQuery(query, corelatedCriteria, eventTable, builderOptions);

    // Occurrence criteria
    String occurrenceCriteria = String.format(
            "HAVING COUNT(%s%s) %s %d",
            corelatedCriteria.occurrence.isDistinct ? "DISTINCT " : "",
            countColumnExpression,
            getOccurrenceOperator(corelatedCriteria.occurrence.type),
            corelatedCriteria.occurrence.count
    );

    query = StringUtils.replace(query, "@occurrenceCriteria", occurrenceCriteria);

    return query;
  }

// <editor-fold defaultstate="collapsed" desc="ICriteriaSqlDispatcher implementation">

  protected <T extends Criteria> String getCriteriaSql(CriteriaSqlBuilder<T> builder, T criteria, BuilderOptions options) {
    String query = builder.getCriteriaSql(criteria, options);
    return processCorrelatedCriteria(query, criteria, options == null ? false : options.isUseDatetime(), options);
  }

  protected <T extends Criteria> String getCriteriaSql(CriteriaSqlBuilder<T> builder, T criteria) {
    return this.getCriteriaSql(builder, criteria, null);
  }

  protected String processCorrelatedCriteria(String query, Criteria criteria, Boolean useDatetime,
          BuilderOptions options) {
    if (criteria.CorrelatedCriteria != null && !criteria.CorrelatedCriteria.isEmpty()) {
        query = wrapCriteriaQuery(query, criteria.CorrelatedCriteria, useDatetime, options, criteria);
    }
    return query;
  }

  @Override
  public String getCriteriaSql(ConditionEra criteria, BuilderOptions options) {
    return getCriteriaSql(conditionEraSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(ConditionOccurrence criteria, BuilderOptions options) {
    return getCriteriaSql(conditionOccurrenceSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(Death criteria, BuilderOptions options) {
    return getCriteriaSql(deathSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(DeviceExposure criteria, BuilderOptions options) {
    return getCriteriaSql(deviceExposureSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(DoseEra criteria, BuilderOptions options) {
    return getCriteriaSql(doseEraSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(DrugEra criteria, BuilderOptions options) {
    return getCriteriaSql(drugEraSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(DrugExposure criteria, BuilderOptions options) {
    return getCriteriaSql(drugExposureSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(Measurement criteria, BuilderOptions options) {
    return getCriteriaSql(measurementSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(Observation criteria, BuilderOptions options) {
    return getCriteriaSql(observationSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(ObservationPeriod criteria, BuilderOptions options) {
    return getCriteriaSql(observationPeriodSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(PayerPlanPeriod criteria, BuilderOptions options) {
    return getCriteriaSql(payerPlanPeriodSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(ProcedureOccurrence criteria, BuilderOptions options) {
    return getCriteriaSql(procedureOccurrenceSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(Specimen criteria, BuilderOptions options) {
    return getCriteriaSql(specimenSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(VisitOccurrence criteria, BuilderOptions options) {
    return getCriteriaSql(visitOccurrenceSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(VisitDetail criteria, BuilderOptions options) {
    return getCriteriaSql(visitDetailSqlBuilder, criteria, options);
  }

  @Override
  public String getCriteriaSql(LocationRegion criteria, BuilderOptions options) {
    return getCriteriaSql(locationRegionSqlBuilder, criteria, options);
  }

// </editor-fold>
// <editor-fold defaultstate="collapsed" desc="IEndStrategyDispatcher implementation">
  private String getDateFieldForOffsetStrategy(DateOffsetStrategy.DateField dateField) {
    switch (dateField) {
      case StartDate:
        return "start_date";
      case EndDate:
        return "end_date";
    }
    return "start_date";
  }

  @Override
  public String getStrategySql(DateOffsetStrategy strat, String eventTable, Boolean retainCohortCovariates) {
    String strategySql = StringUtils.replace(DATE_OFFSET_STRATEGY_TEMPLATE, "@eventTable", eventTable);
    if (strat.offsetUnit == null || IntervalUnit.DAY.getName().equals(strat.offsetUnit)) {
      strategySql = StringUtils.replace(strategySql, "@offsetUnitValue", Integer.toString(strat.offset));
      strategySql = StringUtils.replace(strategySql, "@offsetUnit", "day");
    } else {
      strategySql = StringUtils.replace(strategySql, "@offsetUnitValue", Integer.toString(strat.offsetUnitValue));
      strategySql = StringUtils.replace(strategySql, "@offsetUnit", strat.offsetUnit);
    }
    strategySql = StringUtils.replace(strategySql, "@dateField", getDateFieldForOffsetStrategy(strat.dateField));

    if (retainCohortCovariates) {
        strategySql = StringUtils.replace(strategySql, "@concept_id", ", concept_id");
    } else {
        strategySql = StringUtils.replace(strategySql, "@concept_id", "");
    }
    
    return strategySql;
  }

  @Override
  public String getStrategySql(CustomEraStrategy strat, String eventTable, Boolean retainCohortCovariates) {

    if (strat.drugCodesetId == null) {
      throw new RuntimeException("Drug Codeset ID can not be NULL.");
    }

    String drugExposureEndDateExpression = DEFAULT_DRUG_EXPOSURE_END_DATE_EXPRESSION;
    if (strat.daysSupplyOverride != null && IntervalUnit.DAY.getName().equals(strat.gapUnit)) {
      drugExposureEndDateExpression = String.format("DATEADD(day,%d,DRUG_EXPOSURE_START_DATE)", strat.daysSupplyOverride);
    }else if(strat.daysSupplyOverride != null && IntervalUnit.HOUR.getName().equals(strat.gapUnit)){
      drugExposureEndDateExpression = String.format("DATEADD(hour,%d,DRUG_EXPOSURE_START_DATE)", strat.daysSupplyOverride);
    }else if(strat.daysSupplyOverride != null && IntervalUnit.MINUTE.getName().equals(strat.gapUnit)){
      drugExposureEndDateExpression = String.format("DATEADD(minute,%d,DRUG_EXPOSURE_START_DATE)", strat.daysSupplyOverride);
    }else if(strat.daysSupplyOverride != null && IntervalUnit.SECOND.getName().equals(strat.gapUnit)){
      drugExposureEndDateExpression = String.format("DATEADD(second,%d,DRUG_EXPOSURE_START_DATE)", strat.daysSupplyOverride);
    }
    String strategySql = StringUtils.replace(CUSTOM_ERA_STRATEGY_TEMPLATE, "@eventTable", eventTable);
    strategySql = StringUtils.replace(strategySql, "@drugCodesetId", strat.drugCodesetId.toString());
    if (IntervalUnit.DAY.getName().equals(strat.gapUnit)) {
      strategySql = StringUtils.replace(strategySql, "@gapUnitValue", Integer.toString(strat.gapDays));
      strategySql = StringUtils.replace(strategySql, "@gapUnit", "day");
    }else {
      strategySql = StringUtils.replace(strategySql, "@gapUnitValue", Integer.toString(strat.gapUnitValue));
      strategySql = StringUtils.replace(strategySql, "@gapUnit", strat.gapUnit);

    }
    if(IntervalUnit.DAY.getName().equals(strat.offsetUnit) || strat.offsetUnit == null){
      strategySql = StringUtils.replace(strategySql, "@offsetUnitValue", Integer.toString(strat.offset));
      strategySql = StringUtils.replace(strategySql, "@offsetUnit", "day");
    }else {
      strategySql = StringUtils.replace(strategySql, "@offsetUnitValue", Integer.toString(strat.offsetUnitValue));
      strategySql = StringUtils.replace(strategySql, "@offsetUnit", strat.offsetUnit);
    }

    strategySql = StringUtils.replace(strategySql, "@drugExposureEndDateExpression", drugExposureEndDateExpression);

    if (retainCohortCovariates) {
        strategySql = StringUtils.replace(strategySql, "@concept_id", ", concept_id");
    } else {
        strategySql = StringUtils.replace(strategySql, "@concept_id", "");
    }
    
    return strategySql;
  }

// </editor-fold>

}
