-- The antifraud settings and lists are central's now (its V1043), one policy for every cell. af_settings and af_list_entry
-- stay as this cell's copy of it, which the instances write when central's version is not the copy's and which a start
-- without central reads; hit_count and last_hit_at stay this cell's own. af_list_version holds the version of central's
-- lists the copy was made at, and af_settings.version that of central's settings.
--
-- Who changed the lists is recorded where they are changed, in central: the copy has no audit.
DROP TABLE af_list_audit;
