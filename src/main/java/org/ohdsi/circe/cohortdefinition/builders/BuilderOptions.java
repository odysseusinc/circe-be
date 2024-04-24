package org.ohdsi.circe.cohortdefinition.builders;

import java.util.ArrayList;
import java.util.List;

public class BuilderOptions {

  public List<CriteriaColumn> additionalColumns = new ArrayList<>();
  
  private boolean useDatetime;
  
  private boolean retainCohortCovariates;
  
  private boolean primaryCriteria = false;

  public boolean isUseDatetime() {
    return useDatetime;
  }

  public void setUseDatetime(Boolean useDatetime) {
    this.useDatetime = useDatetime == null ? false: useDatetime;
  }
  
  public boolean isRetainCohortCovariates() {
      return retainCohortCovariates;
  }
  
  public void setRetainCohortCovariates(boolean retainCohortCovariates) {
      this.retainCohortCovariates = retainCohortCovariates;
  }
  
  public boolean isPrimaryCriteria() {
      return primaryCriteria;
  }
  
  public void setPrimaryCriteria(boolean primaryCriteria) {
      this.primaryCriteria = primaryCriteria;
  }
  
}
