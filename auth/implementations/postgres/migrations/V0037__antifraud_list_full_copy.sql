-- When this cell last took central's lists whole (versolauth/versola#1052). Between two whole copies the lists travel as
-- changes since the version of the copy, and a whole copy is taken again every few hours so that a copy that drifted for
-- any reason is repaired. The time is kept beside the version, under the same lock, so that the cell takes one whole copy
-- and not one per instance. NULL before the first whole copy: the next round takes one.
ALTER TABLE af_list_version ADD COLUMN full_copied_at TIMESTAMPTZ;
