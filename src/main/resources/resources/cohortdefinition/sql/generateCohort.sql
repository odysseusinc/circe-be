@codesetQuery

-- concept_id: temp identify of #qualified_events. Created to join the qualified_events, inclusion_events, strategy_ends tables

SELECT event_id, person_id, start_date, end_date, op_start_date, op_end_date, visit_occurrence_id@concept_id 
INTO #qualified_events
FROM
(
  select pe.event_id, pe.person_id, pe.start_date, pe.end_date, pe.op_start_date, pe.op_end_date@pe_concept_id, row_number() over (partition by pe.person_id order by pe.start_date @QualifiedEventSort) as ordinal, cast(pe.visit_occurrence_id as bigint) as visit_occurrence_id
  FROM (@primaryEventsQuery) pe
  @additionalCriteriaQuery
) QE
@QualifiedLimitFilter
;

--- Inclusion Rule Inserts

@inclusionCohortInserts

select event_id, person_id, start_date, end_date, op_start_date, op_end_date@concept_id  @allInclusionColumnsInserts
into #included_events
FROM (
  SELECT event_id, person_id, start_date, end_date, op_start_date, op_end_date, row_number() over (partition by person_id order by start_date @IncludedEventSort) as ordinal@concept_id@allInclusionColumnsInserts 
  from
  (
    select Q.event_id, Q.person_id, Q.start_date, Q.end_date, Q.op_start_date, Q.op_end_date, SUM(coalesce(POWER(cast(2 as bigint), I.inclusion_rule_id), 0)) as inclusion_rule_mask  @Qconcept_id@allInclusionColumnsInserts
    from #qualified_events Q
    LEFT JOIN #inclusion_events I on I.person_id = Q.person_id and I.event_id = Q.event_id
    GROUP BY Q.event_id, Q.person_id, Q.start_date, Q.end_date, Q.op_start_date, Q.op_end_date @Qconcept_id@allInclusionColumnsInserts
  ) MG -- matching groups
{@ruleTotal != 0}?{
  -- the matching group with all bits set ( POWER(2,# of inclusion rules) - 1 = inclusion_rule_mask
  WHERE (MG.inclusion_rule_mask = POWER(cast(2 as bigint),@ruleTotal)-1)
}
) Results
@ResultLimitFilter
;

@strategy_ends_temp_tables

-- generate cohort periods into #final_cohort
select person_id, start_date, end_date 
INTO #cohort_rows
from ( -- first_ends
	select F.person_id, F.start_date, F.end_date 
	FROM (
	  select I.event_id, I.person_id, I.start_date, CE.end_date, row_number() over (partition by I.person_id, I.event_id order by CE.end_date) as ordinal 
	  from #included_events I
	  join ( -- cohort_ends
-- cohort exit dates
@cohort_end_unions
    ) CE on I.event_id = CE.event_id and I.person_id = CE.person_id and CE.end_date >= I.start_date
	) F
	WHERE F.ordinal = 1
) FE;

select person_id, min(start_date) as start_date, DATEADD(day,-1 * @eraconstructorpad, max(end_date)) as end_date 
into #final_cohort
from (
  select person_id, start_date, end_date, sum(is_start) over (partition by person_id order by start_date, is_start desc rows unbounded preceding) group_idx @addColumnQeTempId
  from (
    select person_id, start_date, end_date, 
      case when max(end_date) over (partition by person_id order by start_date rows between unbounded preceding and 1 preceding) >= start_date then 0 else 1 end is_start
    from (
      select person_id, start_date, DATEADD(day,@eraconstructorpad,end_date) as end_date
      from #cohort_rows
    ) CR
  ) ST
) GR
group by person_id, group_idx;
DELETE FROM @target_database_schema.@target_cohort_table where @cohort_id_field_name = @target_cohort_id;
INSERT INTO @target_database_schema.@target_cohort_table (@cohort_id_field_name, subject_id, cohort_start_date, cohort_end_date)
@finalCohortQuery
;

{@generateStats != 0}?{
-- BEGIN: Censored Stats

delete from @results_database_schema.cohort_censor_stats where @cohort_id_field_name = @target_cohort_id;
@cohortCensoredStatsQuery
-- END: Censored Stats
}
{@generateStats != 0 & @ruleTotal != 0}?{

@inclusionRuleTable

-- Find the event that is the 'best match' per person.
-- the 'best match' is defined as the event that satisfies the most inclusion rules.
-- ties are solved by choosing the event that matches the earliest inclusion rule, and then earliest.

select q.person_id, q.event_id 
into #best_events
from #qualified_events Q
join (
	SELECT R.person_id, R.event_id, ROW_NUMBER() OVER (PARTITION BY R.person_id ORDER BY R.rule_count DESC,R.min_rule_id ASC, R.start_date ASC) AS rank_value 
	FROM (
		SELECT Q.person_id, Q.event_id, COALESCE(COUNT(DISTINCT I.inclusion_rule_id), 0) AS rule_count, COALESCE(MIN(I.inclusion_rule_id), 0) AS min_rule_id, Q.start_date 
		FROM #qualified_events Q
		LEFT JOIN #inclusion_events I ON q.person_id = i.person_id AND q.event_id = i.event_id
		GROUP BY Q.person_id, Q.event_id, Q.start_date 
	) R
) ranked on Q.person_id = ranked.person_id and Q.event_id = ranked.event_id
WHERE ranked.rank_value = 1
;

-- modes of generation: (the same tables store the results for the different modes, identified by the mode_id column)
-- 0: all events
-- 1: best event


-- BEGIN: Inclusion Impact Analysis - event
@inclusionImpactAnalysisByEventQuery
-- END: Inclusion Impact Analysis - event

-- BEGIN: Inclusion Impact Analysis - person
@inclusionImpactAnalysisByPersonQuery
-- END: Inclusion Impact Analysis - person

-- If retain_cohort_covariates is checked, it is processed to create the #final_cohort_details table
{@retain_cohort_covariates == 1}?{
-- BEGIN: Retain Cohort Covariates
select qe.op_start_date, qe.op_end_date, qe.visit_occurrence_id,
      ie.*
      @strategy_ends_columns
into #final_cohort_details
from #qualified_events qe
left join inclusion_events ie on qe.person_id = ie.person_id AND qe.event_id = ie.event_id
@leftjoinEraStrategy
;

-- If @results_database_schema."cohort_details_@result_cohort_id" exists, remove it and create new one.
DROP TABLE IF EXISTS @results_database_schema."cohort_details_@result_cohort_id";

select fc.*
into @results_database_schema."cohort_details_@result_cohort_id"
from #final_cohort_details fc
;
-- END: Retain Cohort Covariates
TRUNCATE TABLE #final_cohort_details;
DROP TABLE #final_cohort_details;
}

-- TRUNCATE TABLE #best_events;
-- DROP TABLE #best_events;

-- TRUNCATE TABLE #inclusion_rules;
-- DROP TABLE #inclusion_rules;
}

@strategy_ends_cleanup
-- TRUNCATE TABLE #cohort_rows;
-- DROP TABLE #cohort_rows;

-- TRUNCATE TABLE #final_cohort;
-- DROP TABLE #final_cohort;

-- TRUNCATE TABLE #inclusion_events;
-- DROP TABLE #inclusion_events;

-- TRUNCATE TABLE #qualified_events;
-- DROP TABLE #qualified_events;

-- TRUNCATE TABLE #included_events;
-- DROP TABLE #included_events;

TRUNCATE TABLE #Codesets;
DROP TABLE #Codesets;