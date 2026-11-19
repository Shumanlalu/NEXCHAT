-- NEXCHAT storage buckets.
--
-- The current Android client does not upload files to Supabase Storage yet:
-- attachments travel as encrypted bytes inside Signal envelopes. These buckets
-- are provisioned so avatars and future encrypted attachments can move without
-- another schema change. Uploads are restricted to the authenticated owner's
-- folder (`<auth.uid()>/...`). Until an account is linked to Supabase Auth,
-- these policies intentionally allow no writes.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
    ('avatars', 'avatars', true, 5242880,
        array['image/jpeg', 'image/png', 'image/webp']),
    ('attachments', 'attachments', false, 26214400, null)
on conflict (id) do nothing;

-- Public avatar reads are safe; the bucket contains only profile images.
create policy avatars_public_read on storage.objects
    for select to public
    using (bucket_id = 'avatars');

create policy avatars_owner_insert on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy avatars_owner_update on storage.objects
    for update to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    )
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy avatars_owner_delete on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- Private attachments are readable only by their uploader. Sharing with message
-- recipients must be added together with the client-side upload flow.
create policy attachments_owner_read on storage.objects
    for select to authenticated
    using (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy attachments_owner_insert on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy attachments_owner_delete on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'attachments'
        and (storage.foldername(name))[1] = auth.uid()::text
    );
