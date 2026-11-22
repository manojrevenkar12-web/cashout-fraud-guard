variable "project_id" {
  type        = string
  description = "GCP project that runs the guard."
}

variable "region" {
  type        = string
  default     = "europe-west3"
  description = "Region for Cloud SQL, Pub/Sub and Artifact Registry."
}

variable "network" {
  type        = string
  description = "Self link of the VPC the GKE cluster uses; Cloud SQL gets a private IP on it."
}

variable "k8s_namespace" {
  type    = string
  default = "fraud"
}

variable "db_tier" {
  type    = string
  default = "db-custom-2-7680"
}
